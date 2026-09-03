package com.ruoyi.adapter.kb;

import com.ruoyi.ai.contract.core.InvocationContext;
import com.ruoyi.ai.contract.kb.DocumentSource;
import com.ruoyi.ai.contract.kb.IndexOperation;
import com.ruoyi.ai.contract.kb.IndexRequest;
import com.ruoyi.ai.contract.kb.IndexSelector;
import com.ruoyi.ai.contract.kb.IndexStatus;
import com.ruoyi.ai.contract.kb.KnowledgeIndexPort;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.KbDocument;
import com.ruoyi.system.kb.KbDocStatus;
import com.ruoyi.system.service.IKbDocumentService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** 当前“已上传文档重建索引”流程到 KnowledgeIndexPort 的适配器。 */
@Component
public class KbKnowledgeIndexAdapter implements KnowledgeIndexPort
{
    private final IKbDocumentService documents;
    private final ConcurrentMap<String, Operation> operations = new ConcurrentHashMap<>();

    public KbKnowledgeIndexAdapter(IKbDocumentService documents)
    {
        this.documents = documents;
    }

    @Override
    public IndexOperation start(IndexRequest request, InvocationContext context)
    {
        Long kbId = number(request.knowledgeBaseId(), "knowledgeBaseId");
        List<Long> documentIds = new ArrayList<>();
        for (DocumentSource source : request.documents())
        {
            Long docId = number(source.documentId(), "documentId");
            KbDocument document = documents.selectKbDocumentById(docId);
            if (document == null || !kbId.equals(document.getKbId()))
            {
                throw new ServiceException("索引文档不存在或不属于知识库: " + source.documentId());
            }
            documentIds.add(docId);
        }
        if (documentIds.isEmpty()) throw new ServiceException("索引文档不能为空");
        String operationId = UUID.randomUUID().toString();
        cleanupExpired();
        operations.put(operationId, new Operation(kbId, List.copyOf(documentIds),
                System.currentTimeMillis()));
        for (Long docId : documentIds) documents.reprocess(docId);
        return new IndexOperation(operationId, request.knowledgeBaseId(), IndexStatus.State.RUNNING);
    }

    @Override
    public IndexStatus status(String operationId, InvocationContext context)
    {
        Operation operation = operations.get(operationId);
        if (operation == null)
        {
            return new IndexStatus(operationId, IndexStatus.State.FAILED, 0,
                    List.of("索引操作不存在或已过期"));
        }
        int progress = 0;
        boolean failed = false;
        boolean completed = true;
        List<String> errors = new ArrayList<>();
        for (Long docId : operation.documentIds())
        {
            KbDocument document = documents.selectKbDocumentById(docId);
            if (document == null)
            {
                failed = true;
                errors.add("文档不存在: " + docId);
                continue;
            }
            progress += document.getProgress() == null ? 0 : document.getProgress();
            KbDocStatus state = parseStatus(document.getStatus());
            failed |= state == KbDocStatus.FAILED;
            completed &= state != null && state.isTerminal();
            if (state == KbDocStatus.FAILED) errors.add("文档索引失败: " + docId);
        }
        int average = progress / operation.documentIds().size();
        IndexStatus.State state = failed ? IndexStatus.State.FAILED
                : completed ? IndexStatus.State.COMPLETED : IndexStatus.State.RUNNING;
        return new IndexStatus(operationId, state, average, errors);
    }

    @Override
    public void delete(IndexSelector selector, InvocationContext context)
    {
        Long kbId = number(selector.knowledgeBaseId(), "knowledgeBaseId");
        List<Long> ids = selector.documentIds().stream()
                .map(id -> number(id, "documentId"))
                .peek(id -> {
                    KbDocument doc = documents.selectKbDocumentById(id);
                    if (doc == null || !kbId.equals(doc.getKbId()))
                        throw new ServiceException("删除文档不存在或不属于知识库: " + id);
                }).toList();
        if (!ids.isEmpty()) documents.deleteDocumentByIds(ids.toArray(Long[]::new));
    }

    private static Long number(String value, String field)
    {
        try { return Long.valueOf(value); }
        catch (Exception e) { throw new ServiceException(field + " 必须是数字 ID"); }
    }

    private static KbDocStatus parseStatus(String value)
    {
        try { return value == null ? null : KbDocStatus.valueOf(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private void cleanupExpired()
    {
        long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        operations.entrySet().removeIf(entry -> entry.getValue().createdAt() < cutoff);
    }

    private record Operation(Long kbId, List<Long> documentIds, long createdAt) { }
}
