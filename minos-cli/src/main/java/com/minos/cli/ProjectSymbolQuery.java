package com.minos.cli;

/**
 * Compatibility alias for the application-level project query port.
 *
 * @deprecated use {@link com.minos.application.ProjectSymbolQuery} directly.
 */
@Deprecated(forRemoval = false)
@FunctionalInterface
public interface ProjectSymbolQuery extends com.minos.application.ProjectSymbolQuery {
}
