package ai.doctruth.internal.providers.anthropic.wire;

import java.util.List;

/**
 * Anthropic {@code POST /v1/messages} request body. Public final fields mirror the wire
 * JSON exactly so Jackson serialises without accessor indirection or custom naming rules.
 *
 * @hidden
 */
public final class MessagesRequest {

    public final String model;
    public final int max_tokens;
    public final List<SystemBlock> system;
    public final List<Message> messages;
    public final List<Tool> tools;
    public final ToolChoice tool_choice;

    public MessagesRequest(
            String model,
            int max_tokens,
            List<SystemBlock> system,
            List<Message> messages,
            List<Tool> tools,
            ToolChoice tool_choice) {
        this.model = model;
        this.max_tokens = max_tokens;
        this.system = List.copyOf(system);
        this.messages = List.copyOf(messages);
        this.tools = List.copyOf(tools);
        this.tool_choice = tool_choice;
    }
}
