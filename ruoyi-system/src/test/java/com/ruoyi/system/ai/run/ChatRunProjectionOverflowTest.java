package com.ruoyi.system.ai.run;

import java.nio.file.Files;
import java.nio.file.Path;

import com.ruoyi.system.ai.ContextFileStore;
import com.ruoyi.system.domain.AiChatRunStep;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.mapper.AiChatRunStepMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChatRunProjectionOverflowTest
{
    @TempDir
    Path tmp;

    @Test
    void oversizedToolOutputIsPreviewPlusFile()
    {
        AiChatRunStepMapper steps = mock(AiChatRunStepMapper.class);
        AiChatRunMapper runs = mock(AiChatRunMapper.class);
        ContextFileStore store = new ContextFileStore(tmp.toString());
        ChatRunProjectionService service = new ChatRunProjectionService(steps, runs, store, 100);
        String full = "Z".repeat(250);

        service.project(event(1, "{\"type\":\"tool_end\",\"stepId\":\"call-1\",\"name\":\"readFile\","
                + "\"source\":\"builtin\",\"result\":\"" + full + "\",\"ok\":true,\"ms\":3}"));

        ArgumentCaptor<AiChatRunStep> captor = ArgumentCaptor.forClass(AiChatRunStep.class);
        verify(steps).upsert(captor.capture());
        AiChatRunStep saved = captor.getValue();
        assertThat(saved.getOutputData()).hasSizeLessThanOrEqualTo(120);
        assertThat(saved.getOutputData()).startsWith("Z".repeat(100));
        assertThat(saved.getOutputDataPath()).isNotBlank();
        assertThat(store.loadExternal(saved.getOutputDataPath())).isEqualTo(full);
    }

    @Test
    void streamingContentIsNotOverflowed()
    {
        AiChatRunStepMapper steps = mock(AiChatRunStepMapper.class);
        AiChatRunMapper runs = mock(AiChatRunMapper.class);
        ContextFileStore store = new ContextFileStore(tmp.toString());
        ChatRunProjectionService service = new ChatRunProjectionService(steps, runs, store, 100);
        String full = "C".repeat(250);

        service.project(event(1, "{\"type\":\"text\",\"stepId\":\"answer\",\"text\":\"" + full + "\"}"));
        service.project(event(2, "{\"type\":\"done\"}"));

        ArgumentCaptor<AiChatRunStep> captor = ArgumentCaptor.forClass(AiChatRunStep.class);
        verify(steps).upsert(captor.capture());
        AiChatRunStep saved = captor.getValue();
        assertThat(saved.getStepType()).isEqualTo("content");
        assertThat(saved.getOutputData()).isEqualTo(full);
        assertThat(saved.getOutputDataPath()).isNull();
    }

    @Test
    void snapshotRestoreDoesNotReadOverflowFiles()
    {
        ContextFileStore store = mock(ContextFileStore.class);
        assertThat(ChatRunService.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getType)
                .doesNotContain(ContextFileStore.class);
        verify(store, never()).loadExternal(org.mockito.ArgumentMatchers.any());
    }

    private static ChatRunEventEnvelope event(long seq, String json)
    {
        return new ChatRunEventEnvelope("node-1", "run-1", "session-1", seq, json, seq);
    }
}
