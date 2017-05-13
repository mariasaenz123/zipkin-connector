package com.mulesoft.consulting.zipkinloggerconnector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.mule.api.MuleContext;
import org.mule.api.MuleEvent;
import org.mule.api.annotations.Config;
import org.mule.api.annotations.Connector;
import org.mule.api.annotations.MetaDataScope;
import org.mule.api.annotations.Processor;
import org.mule.api.annotations.lifecycle.Start;
import org.mule.api.annotations.lifecycle.Stop;
import org.mule.api.annotations.param.Default;
import org.mule.api.annotations.param.MetaDataKeyParam;
import org.mule.api.annotations.param.MetaDataKeyParamAffectsType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mulesoft.consulting.zipkinloggerconnector.config.AbstractConfig;
import com.mulesoft.consulting.zipkinloggerconnector.config.ZipkinConsoleConnectorConfig;
import com.mulesoft.consulting.zipkinloggerconnector.config.ZipkinHttpConnectorConfig;
import com.mulesoft.consulting.zipkinloggerconnector.datasense.DefaultCategory;
import com.mulesoft.consulting.zipkinloggerconnector.model.LoggerData;
import com.mulesoft.consulting.zipkinloggerconnector.model.LoggerTag;
import com.mulesoft.consulting.zipkinloggerconnector.model.TraceData;

import brave.Span.Kind;
import brave.Tracer;
import brave.Tracing;
import brave.propagation.Propagation.Getter;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContextOrSamplingFlags;
import zipkin.Endpoint;
import zipkin.Span;
import zipkin.reporter.AsyncReporter;
import zipkin.reporter.Reporter;
import zipkin.reporter.okhttp3.OkHttpSender;

/**
 * @author michaelhyatt
 *
 */
@Connector(name = "zipkin-logger", friendlyName = "Zipkin Logger")
@MetaDataScope(DefaultCategory.class)
public class ZipkinLoggerConnector {

	@Config
	AbstractConfig config;

	@Inject
	MuleContext muleContext;

	private Tracing tracing;
	private AsyncReporter<Span> reporter = null;

	private Tracer tracer;
	private Map<String, brave.Span> spansInFlight = new HashMap<String, brave.Span>();

	private String serviceName;

	private static Logger logger = LoggerFactory.getLogger(ZipkinLoggerConnector.class);

	/*
	 * Initialise Zipkin connector
	 */
	@Start
	public void init() {
		if (config instanceof ZipkinHttpConnectorConfig) {

			ZipkinHttpConnectorConfig httpConfig = (ZipkinHttpConnectorConfig) config;
			// Configure a reporter, which controls how often spans are sent
			// (the dependency is io.zipkin.reporter:zipkin-sender-okhttp3)
			OkHttpSender sender = OkHttpSender.create(httpConfig.getZipkinUrl());
			reporter = (AsyncReporter<Span>) AsyncReporter.builder(sender).build();

			// Create a tracing component with the service name you want to see
			// in Zipkin.
			tracing = Tracing.newBuilder().localServiceName(httpConfig.getServiceName()).reporter(reporter).build();

			serviceName = httpConfig.getServiceName();
		} else if (config instanceof ZipkinConsoleConnectorConfig) {
			Reporter<Span> reporter = Reporter.CONSOLE;

			ZipkinConsoleConnectorConfig consoleConfig = (ZipkinConsoleConnectorConfig) config;
			tracing = Tracing.newBuilder().localServiceName(consoleConfig.getServiceName()).reporter(reporter).build();

			serviceName = consoleConfig.getServiceName();
		} else {
			throw new RuntimeException("Unknown configuration option");
		}

		// Tracing exposes objects you might need, most importantly the
		// tracer
		tracer = tracing.tracer();
	}

	/*
	 * Shutdown Zipkin connector
	 */
	@Stop
	public void shutdown() {
		// When all tracing tasks are complete, close the tracing component and
		// reporter
		// This might be a shutdown hook for some users
		tracing.close();

		if (reporter != null)
			reporter.close();
	}

	/**
	 * Creates new span that can be a standalone span, or part of a parent span
	 * coming with the request
	 * 
	 * @param muleEvent
	 *            injected at runtime
	 * @param standaloneOrJoinedSpan
	 *            New standalone span, or join another parent span
	 * @param spanTags
	 *            collection of tags and parent information passed in the
	 *            payload
	 * @param spanType
	 *            SERVER or CLIENT type of request
	 * @param spanName
	 *            Name of the span
	 * @param flowVariableToSetWithId
	 *            flow variable to populate with spanId
	 * 
	 * @return created span object
	 */
	@Processor
	public TraceData createAndStartSpan(MuleEvent muleEvent,
			@MetaDataKeyParam(affects = MetaDataKeyParamAffectsType.BOTH) String standaloneOrJoinedSpan,
			@Default("#[payload]") Object spanTags, @Default(value = "SERVER") Kind spanType,
			@Default(value = "myspan") String spanName, @Default(value = "spanId") String flowVariableToSetWithId) {

		// Check if the context is propagated from incoming call
		brave.Span span = createOrJoinSpan((LoggerData) spanTags, standaloneOrJoinedSpan);

		span.name(spanName).kind(spanType);

		// Get the tags
		extractTags((LoggerData) spanTags, span);

		span.remoteEndpoint(Endpoint.builder().serviceName(serviceName).build());

		span.start();

		String spanId = Long.toHexString(span.context().spanId());

		// Set the flowVar with created spanId
		muleEvent.setFlowVariable(flowVariableToSetWithId, spanId);

		// Store span for future lookup
		spansInFlight.put(spanId, span);

		// ParentId propagation: if it is a standalone span, populate it with
		// spanId,
		// if joined span, propagate the parentId
		String parentId = null;
		if ("join_id".equals(standaloneOrJoinedSpan)) {
			Long parentIdValue = span.context().parentId();
			parentId = Long.toHexString(parentIdValue != null ? parentIdValue : span.context().spanId());
		} else if ("standalone_id".equals(standaloneOrJoinedSpan)) {
			parentId = Long.toHexString(span.context().spanId());
		}

		if (parentId == null) {
			throw new RuntimeException("Unable to populate span parentId. Check the propagation mapping.");
		}

		TraceData traceDataToReturn = new TraceData(Long.toHexString(span.context().traceId()),
				Long.toHexString(span.context().spanId()), parentId, span.context().sampled() ? "1" : "0",
				span.context().debug() ? "1" : "0");

		return traceDataToReturn;
	}

	/**
	 * Closes the existing span and causes it to be logged. Requires span
	 * creation prior to calling.
	 * 
	 * @param spanIdExpr
	 *            MEL expression to result in spanId.
	 * @return
	 */
	@Processor
	public void finishSpan(@Default(value = "#[flowVars.spanId]") String spanIdExpr) {

		String spanId = (String) muleContext.getExpressionLanguage().evaluate(spanIdExpr);

		brave.Span span = spansInFlight.remove(spanId);

		if (span != null) {
			span.finish();
		} else {
			throw new RuntimeException("Span " + spanId + " not found");
		}

	}

	/**
	 * @param spanTags
	 * @param span
	 */
	private void extractTags(LoggerData spanTags, brave.Span span) {

		List<LoggerTag> tags = spanTags.getLoggerTags();

		// Add tags to span
		for (LoggerTag tag : tags) {
			span.tag(tag.getKey(), tag.getValue());
		}
	}

	/**
	 * @param spanCreationData
	 * @param spanCreationType
	 * @return
	 */
	private brave.Span createOrJoinSpan(LoggerData tagData, final String spanCreationType) {

		Extractor<LoggerData> extractor = tracing.propagation().extractor(new Getter<LoggerData, String>() {
			@Override
			public String get(LoggerData message, String key) {
				if ("join_id".equals(spanCreationType)) {
					if ("X-B3-TraceId".equals(key)) {
						return message.getTraceData().getTraceId();
					} else if ("X-B3-ParentSpanId".equals(key)) {
						return message.getTraceData().getParentSpanId();
					} else if ("X-B3-SpanId".equals(key)) {
						return message.getTraceData().getSpanId();
					} else if ("X-B3-Sampled".equals(key)) {
						return message.getTraceData().getSampled();
					} else if ("X-B3-Flags".equals(key)) {
						return message.getTraceData().getDebug();
					}
				}
				return null;

			}
		});

		TraceContextOrSamplingFlags contextOrFlags = extractor.extract(tagData);

		if (contextOrFlags.context() != null) {
			logger.debug("Found parent tags, joining");
			return tracer.joinSpan(contextOrFlags.context());
		} else {
			logger.debug("Starting new span, propagation details not found.");
			return tracer.newTrace(contextOrFlags.samplingFlags());
		}

	}

	public void setConfig(AbstractConfig config) {
		this.config = config;
	}

	public void setMuleContext(MuleContext muleContext) {
		this.muleContext = muleContext;
	}

	public AbstractConfig getConfig() {
		return config;
	}

}