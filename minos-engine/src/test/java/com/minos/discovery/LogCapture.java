package com.minos.discovery;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Capture des traces d'un {@code System.Logger} (adossé à {@code java.util.logging} par défaut) le temps d'un test :
 * le silence d'une exception avalée devient observable. Se ferme pour retirer son gestionnaire.
 */
final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Handler handler;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    LogCapture(Class<?> owner) {
        this.logger = Logger.getLogger(owner.getName());
        this.handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
    }

    /** Les messages de niveau WARNING (ou plus) publiés depuis l'ouverture de la capture. */
    List<String> warnings() {
        return records.stream()
                .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
                .map(LogRecord::getMessage)
                .toList();
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
    }
}
