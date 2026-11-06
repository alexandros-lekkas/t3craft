package dev.maxwellyoung.t3craft;

final class T3McpException extends java.io.IOException {
 enum Kind { AUTH, DENIED, REJECTED, TOOL, PROTOCOL, UNREACHABLE, AMBIGUOUS }
 final Kind kind;
 final String code;
 /** True only when T3 certainly did not execute the operation. */
 final boolean notExecuted;
 T3McpException(Kind kind, String code, boolean notExecuted, String message) {
  super(message); this.kind = kind; this.code = code; this.notExecuted = notExecuted;
 }
}
