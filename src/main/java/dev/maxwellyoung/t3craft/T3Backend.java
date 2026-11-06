package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** Application operations; credentials and wire protocols stay inside each backend. */
public interface T3Backend {
	String ownerKey();
	boolean sameCredentials(T3Backend other);
	JsonObject shell() throws IOException, InterruptedException;
	JsonObject thread(String id, int turns) throws IOException, InterruptedException;
	List<T3Api.Provider> providers(T3Socket socket) throws IOException, InterruptedException;
	void sendPrompt(JsonObject thread, String text, JsonObject model) throws IOException, InterruptedException;
	String startThread(JsonObject template, String text, JsonObject model) throws IOException, InterruptedException;
	void interrupt(String id) throws IOException, InterruptedException;
	void answerQuestions(String id, String request, JsonObject answers) throws IOException, InterruptedException;
	void respondToApproval(String id, String request, String decision) throws IOException, InterruptedException;
	T3Api.Review review(String id) throws IOException, InterruptedException;
	T3Socket openSocket(Consumer<String> closed) throws IOException, InterruptedException;
	default boolean publicMcp() { return false; }
	default boolean hasLegacy() { return true; }
	default void forgetProtocol() {}
	default int negotiatedProtocol() { return 0; }
	default JsonObject adaptShell(JsonObject source) { return source; }
	default JsonObject adaptShellThread(JsonObject source) { return source; }
	static T3Backend forEnvironment(T3Config.Environment env) {
		return env.mcpUrl == null || env.mcpUrl.isBlank() ? new T3Api(env.baseUrl, env.accessToken) : new T3McpBackend(env);
	}
}
