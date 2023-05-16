package com.github.artyomcool.retrace;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StackTraceAnalyzer {

	private static final String AT_PREFIX = "    at ";
	private static final String CAUSED_BY_PREFIX = "Caused by: ";
	private static final String JAVA_SUFFIX = ".java";
	private static final String LOCKED_PREFIX = "      - locked ";
	private static final String HEADER_PREFIX = "\"";
	
	// Start with spaces
	// 1.1.2 : module support added
    private static final Pattern AT_PATTERN = Pattern.compile("\\s+at (?<module>(.*@.*/)|(app//))?(?<class>.*)\\.(?<method>[^.]+)\\((?<moduleAlt>(.*@.*/)|(app//))?(?<source>[^:]+)(:(?<line>\\d+))?\\)");
    
	// Start with spaces
	// 1.1.3 : lock support added : class must be deobfuscated
    private static final Pattern LOCK_PATTERN = Pattern.compile("\\s+\\- locked (?<class>.*)(?<lockid>@.*)");
    private static final Pattern HEADER_PATTERN = Pattern.compile("(?<headerPrefix>.*on lock\\=)(?<class>.*)(?<lockid>@.*)");

	// In comparison, Proguard uses this regex pattern (no module support) :
	// private static final Pattern AT_PATTERN = Pattern.compile("(?:.*?\\bat\\s+%c\\.%m\\s*\\(%s(?::%l)?\\)\\s*)|(?:(?:.*?[:\"]\\s+)?%c(?::.*)?)|(?:.*?\\-\\s+%c\\.%m.*(?:line=(%l)).*)");
	// The last 2 patterns are not supported. Last one is jstack hung.
    
	// 1.1.4 : Jstack and JFR use tabulations (opposed to JZR Recorder that uses spaces)
	private static final String AT_ALT_PREFIX = "\tat ";
	private static final String LOCKED_ALT_PREFIX = "\t- locked ";
	
    // 1.1.4 : different format with JFR : <tab>- locked <0x00000000f351d9d0> (a org.jeyzer.demo.features.c.e)
	private static final Pattern LOCKED_ALT_PATTERN = Pattern.compile("\\s+\\- locked (?<lockid>[^\\(]+)\\(a (?<class>.*)\\)");
    
	// 1.1.4 : Waiting to lock deobfuscation added for JFR (No need for Jzr format : not handled)	
	// 	- waiting to lock <0x00000000f3511948> (a org.jeyzer.demo.features.c.e)
	private static final String WAITING_TO_LOCK_ALT_PREFIX = "\t- waiting to lock ";
	private static final Pattern WAITING_TO_LOCK_PATTERN = Pattern.compile("\\s+\\- waiting to lock (?<lockid>[^\\(]+)\\(a (?<class>.*)\\)");
	
	private final Map<String, ClassMapping> classes;

	public StackTraceAnalyzer(Map<String, ClassMapping> classes) {
		this.classes = classes;
	}

	public void appendLine(StringBuilder builder, String line) {
		if (line.startsWith(AT_PREFIX) || line.startsWith(AT_ALT_PREFIX)) {
			appendAt(builder, line);
		} else if (line.startsWith(LOCKED_PREFIX) || line.startsWith(LOCKED_ALT_PREFIX)) {
			appendLock(builder, line);
		} else if (line.startsWith(WAITING_TO_LOCK_ALT_PREFIX)) {
			appendWaitingToLock(builder, line);
		} else if (line.startsWith(HEADER_PREFIX)) {
			appendHeader(builder, line);
		} else {
			if (line.startsWith(CAUSED_BY_PREFIX)) {
				line = line.substring(CAUSED_BY_PREFIX.length(), line.length());
				builder.append(CAUSED_BY_PREFIX);
			}
			appendException(builder, line);
		}
		builder.append('\n');
	}

	private void appendHeader(StringBuilder builder, String line) {
		Matcher matcher = HEADER_PATTERN.matcher(line);
		if (!matcher.matches()) {
			builder.append(line);
			return;
		}

		String headerName = matcher.group("headerPrefix");
		String className = matcher.group("class");
		String lockidName = matcher.group("lockid");
		
		ClassMapping mapping = classes.get(className);
		if (mapping == null) {
			builder.append(line);
			return;
		}
		
		builder.append(headerName)
				.append(mapping.getName())
				.append(lockidName);
	}

	private void appendLock(StringBuilder builder, String line) {
		boolean jzrPrefix = line.startsWith(LOCKED_PREFIX);
		Matcher matcher = jzrPrefix ? LOCK_PATTERN.matcher(line) : LOCKED_ALT_PATTERN.matcher(line);
		if (!matcher.matches()) {
			builder.append(line);
			return;
		}

		String className = matcher.group("class");
		String lockidName = matcher.group("lockid");
		
		ClassMapping mapping = classes.get(className);
		if (mapping == null) {
			builder.append(line);
			return;
		}
		
		if (jzrPrefix)
			builder.append(LOCKED_PREFIX)
				.append(mapping.getName())
				.append(lockidName);
		else
			builder.append(LOCKED_ALT_PREFIX)
			.append(lockidName)
			.append("(a ")
			.append(mapping.getName())
			.append(")");
	}
	
	private void appendWaitingToLock(StringBuilder builder, String line) {
		Matcher matcher = WAITING_TO_LOCK_PATTERN.matcher(line);
		if (!matcher.matches()) {
			builder.append(line);
			return;
		}

		String className = matcher.group("class");
		String lockidName = matcher.group("lockid");
		
		ClassMapping mapping = classes.get(className);
		if (mapping == null) {
			builder.append(line);
			return;
		}
		
		builder.append(WAITING_TO_LOCK_ALT_PREFIX)
			.append(lockidName)
			.append("(a ")
			.append(mapping.getName())
			.append(")");
	}

	private void appendAt(StringBuilder builder, String line) {
		Matcher matcher = AT_PATTERN.matcher(line);
		if (!matcher.matches()) {
			builder.append(line);
			return;
		}

		String moduleSection = matcher.group("module");
		String className = matcher.group("class");
		String methodName = matcher.group("method");
		String moduleAltSection = matcher.group("moduleAlt"); // JFR
		String source = matcher.group("source"); // No mapping done here. We stay with SourceFile, Unknown Source..
		String lineNumber = matcher.group("line");
		final int lineNumberInt = lineNumber == null ? -1 : Integer.parseInt(lineNumber);
		
		ClassMapping mapping = classes.get(className);
		if (mapping == null) {
			line = updateSource(line);
			builder.append(line);
			return;
		}
		
		// Update the source with class name
		source = mapping.getName() != null ? getClassShortName(mapping.getName(), source) : source;

		builder.append(line.startsWith(AT_PREFIX) ? AT_PREFIX : AT_ALT_PREFIX)
				.append(moduleSection!=null ? moduleSection:"")
				.append(mapping.getName())
				.append('.');
		Collection<MethodMapping> methods = mapping.getMethods(methodName);
		Iterable<MethodMapping> filtered = lineNumberInt == -1
				? methods
				: Iterables.filter(methods, new Predicate<MethodMapping>() {
			@Override
			public boolean apply(MethodMapping input) {
				return input.inRange(lineNumberInt);
			}
		});
		Iterator<MethodMapping> iterator = filtered.iterator();
		if (!iterator.hasNext()) {
			appendMethod(builder, methodName, moduleAltSection, source, lineNumber);
			return;
		}
		MethodMapping first = iterator.next();
		appendMethod(builder, first.getName(), moduleAltSection, source, lineNumber);
		while (iterator.hasNext()) {
			MethodMapping next = iterator.next();
			builder.append("\n                ").append(next.getName()); // we do work with spaces instead of tab
		}
	}

	private String updateSource(String line) {
		int end = line.lastIndexOf('.');
		if (end == -1)
			return line; // nothing to do

		// example : java.lang.Thread$InnerClass.sleep(Native Method)
		int start = line.substring(0, end).lastIndexOf('.');
		if (start == -1)
			return line;

		String classShortName = line.substring(start+1, end);

		if (!Character.isUpperCase(classShortName.charAt(0)))
			return line; // Not a class
		
		// Go for updating the end, stripping any inner class
		int pos = line.indexOf('(', end);
		if (pos == -1 || classShortName.isEmpty())
			return line;

		return line.substring(0, pos+1) + stripInnerClass(classShortName) + JAVA_SUFFIX + ")";
	}

	private String getClassShortName(String name, String defaultValue) {
		int start = name.lastIndexOf('.');
		if (start == -1 || name.substring(start+1).isEmpty())
			return defaultValue;
		
		return stripInnerClass(name.substring(start+1)) + JAVA_SUFFIX;
	}
	
	private String stripInnerClass(String className) {
		int end = className.lastIndexOf('$');
		if (end == -1)
			return className;
		else
			return className.substring(0, end);
	}

	private void appendMethod(StringBuilder builder, String methodName, String altModule, String source, String lineNumber) {
		builder.append(methodName)
				.append('(')
				.append(altModule!=null ? altModule:"")
				.append(source);
		if (lineNumber != null) {
			builder.append(':')
					.append(lineNumber);
		}
		builder.append(')');
	}

	private void appendException(StringBuilder builder, String line) {
		int pos = line.indexOf(':');
		if (pos == -1) {
			builder.append(line);
			return;
		}
		String className = line.substring(0, pos);
		String tail = line.substring(pos, line.length());
		builder.append(resolveClassName(className))
				.append(tail);
	}

	private String resolveClassName(String obfuscatedName) {
		ClassMapping mapping = classes.get(obfuscatedName);
		if (mapping == null) {
			return obfuscatedName;
		}
		return mapping.getName();
	}
}