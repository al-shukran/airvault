package app.lensly.core.parse;

/**
 * The one call the app makes to the Parser Sandbox. [method] picks the parser. The payload and the reply use the wire
 * format of ParserWire, and a reply is a refusal when the sandbox cannot answer.
 */
interface IParserSandbox {
    byte[] call(int method, in byte[] payload);
}
