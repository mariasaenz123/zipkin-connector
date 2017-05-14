package com.mulesoft.consulting.zipkinloggerconnector.automation.functional;

import static org.junit.Assert.*;
import com.mulesoft.consulting.zipkinloggerconnector.ZipkinLoggerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mule.tools.devkit.ctf.junit.AbstractTestCase;

public class JoinExternalSpanAsyncTestCases extends AbstractTestCase<ZipkinLoggerConnector> {

	public JoinExternalSpanAsyncTestCases() {
		super(ZipkinLoggerConnector.class);
	}

	@Before
	public void setup() {
		// TODO
	}

	@After
	public void tearDown() {
		// TODO
	}

	@Test
	public void verify() {
		java.lang.String expected = null;
		java.lang.String logMessage = null;
		java.util.Map<java.lang.String, java.lang.String> additionalTags = null;
		brave.Span.Kind ServerOrClientSpanType = null;
		java.lang.String spanName = null;
		java.lang.String spanId = null;
		java.lang.String parentSpanId = null;
		java.lang.String traceId = null;
		java.lang.String sampled = null;
		java.lang.String flags = null;
		assertEquals(getConnector().joinExternalSpanAsync(logMessage, additionalTags, ServerOrClientSpanType, spanName,
				spanId, parentSpanId, traceId, sampled, flags), expected);
	}

}