package com.ruoyi.adapter.ai.model;

import com.ruoyi.ai.contract.core.InvocationContext;
import com.ruoyi.ai.contract.core.Usage;
import com.ruoyi.ai.contract.model.ChatRequest;
import com.ruoyi.ai.contract.model.EmbeddingRequest;
import com.ruoyi.ai.contract.model.EmbeddingResponse;
import com.ruoyi.ai.contract.model.ModelCapabilities;
import com.ruoyi.ai.contract.model.ModelCapability;
import com.ruoyi.ai.contract.model.ModelChunk;
import com.ruoyi.ai.contract.model.ModelProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow;
import org.reactivestreams.Subscription;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import com.ruoyi.system.ai.ChatModelFactory;
import com.ruoyi.system.ai.EmbeddingModelFactory;

/** Spring AI / DB 动态模型工厂到稳定 ModelProvider 的适配器。 */
@Component
public class SpringAiModelProviderAdapter implements ModelProvider
{
    private final ChatModelFactory chatModels;
    private final EmbeddingModelFactory embeddingModels;

    public SpringAiModelProviderAdapter(ChatModelFactory chatModels,
                                        EmbeddingModelFactory embeddingModels)
    {
        this.chatModels = chatModels;
        this.embeddingModels = embeddingModels;
    }

    @Override
    public String id()
    {
        return "db-openai-compatible";
    }

    @Override
    public ModelCapabilities capabilities()
    {
        return new ModelCapabilities(Set.of(ModelCapability.CHAT, ModelCapability.EMBEDDING),
                true, true, true, 0);
    }

    @Override
    public Flow.Publisher<ModelChunk> chat(ChatRequest request, InvocationContext context)
    {
        Long modelId = parseModelId(request.model());
        Prompt prompt = new Prompt(toMessages(request.messages()));
        return subscriber -> chatModels.get(modelId).stream(prompt).subscribe(
                response -> {
                    String text = response.getResult() == null || response.getResult().getOutput() == null
                            ? "" : response.getResult().getOutput().getText();
                    subscriber.onNext(new ModelChunk("text.delta", text, null, false));
                },
                subscriber::onError,
                subscriber::onComplete,
                subscription -> subscriber.onSubscribe(flowSubscription(subscription)));
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request, InvocationContext context)
    {
        List<float[]> vectors = embeddingModels.get(parseModelId(request.model())).embed(request.inputs());
        return new EmbeddingResponse(vectors, Usage.EMPTY);
    }

    private static Flow.Subscription flowSubscription(Subscription subscription)
    {
        return new Flow.Subscription()
        {
            @Override public void request(long n) { subscription.request(n); }
            @Override public void cancel() { subscription.cancel(); }
        };
    }

    private static List<Message> toMessages(List<ChatRequest.Message> source)
    {
        List<Message> messages = new ArrayList<>();
        for (ChatRequest.Message message : source)
        {
            String role = message.role() == null ? "user" : message.role().toLowerCase();
            messages.add(switch (role)
            {
                case "system" -> new SystemMessage(message.content());
                case "assistant" -> new AssistantMessage(message.content());
                default -> new UserMessage(message.content());
            });
        }
        return messages;
    }

    private static Long parseModelId(String model)
    {
        try
        {
            return Long.valueOf(model);
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("当前 DB 模型适配器要求数字 model id: " + model, e);
        }
    }
}
