package dev.bethge.shipir.proxy.render;

/** One file of the managed proxy configuration: a plain file name (no directories) and its full content. */
public record RenderedFile(String name, String content) {
}
