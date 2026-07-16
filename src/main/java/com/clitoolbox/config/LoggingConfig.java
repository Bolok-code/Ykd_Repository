package com.clitoolbox.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.*;

public class LoggingConfig {

    public static void init() {
        Logger root = Logger.getLogger("");

        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }

        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(Level.INFO);
        console.setFormatter(new BriefFormatter());
        root.addHandler(console);

        try {
            Files.createDirectories(Paths.get("work"));
            FileHandler file = new FileHandler("work/app.log", 5 * 1024 * 1024, 3, true);
            file.setLevel(Level.ALL);
            file.setFormatter(new BriefFormatter());
            root.addHandler(file);
        } catch (IOException e) {
            System.err.println("[警告] 无法创建日志文件: " + e.getMessage());
        }

        root.setLevel(Level.ALL);
    }

    static class BriefFormatter extends Formatter {
        private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss");
        @Override
        public String format(LogRecord r) {
            return SDF.format(new Date(r.getMillis()))
                + " [" + r.getLevel() + "] " + r.getMessage()
                + System.lineSeparator();
        }
    }
}
