package me.venixpll.console;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Represents a single log line in the Athenis console system.
 */
public class LogEntry {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String timestamp;
    private final String level;
    private final String tag;
    private final String message;
    private final String rawText;

    public LogEntry(String level, String rawText) {
        this.timestamp = LocalTime.now().format(TIME_FMT);
        this.level = (level == null || level.isEmpty()) ? "INFO" : level.toUpperCase();
        this.rawText = rawText;

        if (rawText != null && rawText.startsWith("[") && rawText.indexOf(']') > 0) {
            int end = rawText.indexOf(']') + 1;
            this.tag = rawText.substring(0, end);
            this.message = rawText.substring(end).stripLeading();
        } else {
            this.tag = null;
            this.message = rawText != null ? rawText : "";
        }
    }

    public LogEntry(String level, String tag, String message) {
        this.timestamp = LocalTime.now().format(TIME_FMT);
        this.level = (level == null || level.isEmpty()) ? "INFO" : level.toUpperCase();
        this.tag = tag;
        this.message = message != null ? message : "";
        this.rawText = (tag != null ? tag + " " : "") + this.message;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getLevel() {
        return level;
    }

    public String getTag() {
        return tag;
    }

    public String getMessage() {
        return message;
    }

    public String getRawText() {
        return rawText;
    }

    public String getFormatted() {
        if (tag != null) {
            return "[" + timestamp + "] " + tag + " " + message;
        } else {
            return "[" + timestamp + "] " + message;
        }
    }
}
