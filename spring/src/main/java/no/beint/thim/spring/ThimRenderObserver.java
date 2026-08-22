package no.beint.thim.spring;

/**
 * Observes synchronous Thim rendering. The same unique {@link ThimRender} instance is
 * supplied to each lifecycle method. Observer failures are isolated from rendering and
 * from other observers.
 */
public interface ThimRenderObserver {
    default void started(ThimRender render) {
    }

    default void succeeded(ThimRender render) {
    }

    default void failed(ThimRender render, Throwable failure) {
    }
}
