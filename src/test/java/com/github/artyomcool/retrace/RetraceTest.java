package com.github.artyomcool.retrace;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import static org.junit.Assert.assertEquals;

public class RetraceTest {

	@Test
	public void readMapping() throws IOException {
		Retrace retrace = createRetrace();
		ClassMapping retraceClass = retrace.getClass("b.a.a.e");
		assertEquals("com.github.artyomcool.retrace.StackTraceAnalyzer$1", retraceClass.getName());
	}

	@Test
	public void retrace() throws IOException {
		Retrace retrace = createRetrace();
		String retraced = retrace.stackTrace(readResource("/stacktrace.txt"));
		assertEquals("java.lang.RuntimeException: some text\n" +
						"    at com.google.common.base.Joiner.access$000$202dd7f0(Joiner.java:103)\n" +
						"                access$200$7a4c6e58\n" +
						"    at com.github.artyomcool.retrace.StackTraceAnalyzer$1.apply(StackTraceAnalyzer.java:62)\n" +
						"    at com.github.artyomcool.retrace.StackTraceAnalyzer.resolveClassName(StackTraceAnalyzer.java:105)\n" +
						"Caused by: java.lang.IllegalArgumentException: some text 2\n" +
						"    at com.github.artyomcool.retrace.Retrace.stacktrace(Retrace.java:40)\n" +
						"    at some.unknown.method(SourceFile:76)\n" +
						"    at some.unknown.method2(UnknownSource)\n" +
						"    at com.github.artyomcool.retrace.ClassMapping.getObfuscatedName(ClassMapping.java)\n" +
						"                addLine\n" +
						"Caused by: java.lang.NullPointerException\n" +
						"    at com.github.artyomcool.retrace.MethodMapping.toString(MethodMapping.java:45)\n" +
						"    ... 2 more\n",
				retraced);
	}

	private Retrace createRetrace() throws IOException {
		try (BufferedReader reader = readResource("/mapping.txt")) {
			return new Retrace(reader);
		}
	}

	private BufferedReader readResource(String resource) {
		InputStream resourceAsStream = getClass().getResourceAsStream(resource);
		return new BufferedReader(new InputStreamReader(resourceAsStream));
	}

}
