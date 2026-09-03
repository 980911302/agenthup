package com.ruoyi.web.controller.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.system.domain.KbDocument;
import com.ruoyi.system.domain.vo.DesktopViews;
import com.ruoyi.system.kb.KbDocProductError;
import com.ruoyi.system.kb.KbDocProductStatus;
import com.ruoyi.system.kb.access.KbAccessAction;
import com.ruoyi.system.kb.access.KbAuthorizationService;
import com.ruoyi.system.kb.ingest.KbIngestProgressHub;
import com.ruoyi.system.service.IKbDocumentService;

/**
 * 知识库文档 信息操作处理
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai/kb/{kbId}/document")
public class KbDocumentController extends BaseController
{
    @Autowired
    private IKbDocumentService kbDocumentService;

    @Autowired
    private KbAuthorizationService kbAuthorizationService;

    @Autowired
    private KbIngestProgressHub ingestProgressHub;

    /**
     * 知识库文档处理事件流。使用标准 Authorization 请求头鉴权，
     * 客户端通过 fetch 流式读取，不在 URL 中携带 JWT。
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long kbId, HttpServletResponse response)
    {
        kbAuthorizationService.requireKb(kbId, KbAccessAction.READ);
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return ingestProgressHub.subscribe(kbId);
    }

    /**
     * 查询知识库文档列表
     */
    @GetMapping("/list")
    public TableDataInfo list(@PathVariable Long kbId, KbDocument kbDocument)
    {
        kbAuthorizationService.requireKb(kbId, KbAccessAction.READ);
        startPage();
        kbDocument.setKbId(kbId);
        List<KbDocument> list = kbDocumentService.selectKbDocumentList(kbDocument);
        // 附带产品状态；裁剪内部路径，计算 downloadable
        if (list != null)
        {
            for (KbDocument d : list)
            {
                if (d != null)
                {
                    d.setProductStatus(KbDocProductStatus.fromParseStatus(d.getParseStatus()));
                    kbDocumentService.sanitizeForUser(d);
                }
            }
        }
        return getDataTable(list == null ? List.of() : list.stream().map(DesktopViews::document).toList());
    }

    /**
     * 上传文档到知识库(异步摄入)。
     * onDuplicate: skip（默认）| force；replace 本期拒绝（业务 code 400）
     */
    @Log(title = "知识库文档", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult upload(@PathVariable Long kbId,
                             @RequestParam("file") MultipartFile file,
                             @RequestParam(value = "onDuplicate", required = false, defaultValue = "skip") String onDuplicate)
    {
        kbAuthorizationService.requireKb(kbId, KbAccessAction.WRITE);
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("请选择要上传的文件");
        }
        KbDocument doc = kbDocumentService.uploadDocument(kbId, file, getUsername(), onDuplicate);
        boolean duplicate = doc != null && "DUPLICATE".equals(doc.getRemark());
        if (duplicate)
        {
            doc.setRemark(null);
        }
        if (doc != null)
        {
            doc.setProductStatus(KbDocProductStatus.fromParseStatus(doc.getParseStatus()));
            kbDocumentService.sanitizeForUser(doc);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("doc", doc == null ? null : DesktopViews.document(doc));
        data.put("duplicate", duplicate);
        data.put("productStatus", doc == null ? null : doc.getProductStatus());
        return success(data);
    }

    /**
     * 重新处理文档
     */
    @Log(title = "知识库文档", businessType = BusinessType.UPDATE)
    @PostMapping("/{docId}/reprocess")
    public AjaxResult reprocess(@PathVariable Long kbId, @PathVariable Long docId)
    {
        kbAuthorizationService.requireDocInKb(kbId, docId, KbAccessAction.WRITE);
        kbDocumentService.reprocess(docId);
        return success();
    }

    /**
     * 修改文档展示名称。
     */
    @Log(title = "知识库文档", businessType = BusinessType.UPDATE)
    @PutMapping("/{docId}/name")
    public AjaxResult rename(@PathVariable Long kbId, @PathVariable Long docId,
                             @RequestBody Map<String, Object> body)
    {
        kbAuthorizationService.requireDocInKb(kbId, docId, KbAccessAction.WRITE);
        String docName = body == null || body.get("docName") == null
            ? "" : body.get("docName").toString().trim();
        if (StringUtils.isEmpty(docName))
        {
            throw new ServiceException("文档名称不能为空", HttpStatus.BAD_REQUEST);
        }
        if (docName.length() > 255)
        {
            throw new ServiceException("文档名称不能超过255个字符", HttpStatus.BAD_REQUEST);
        }
        KbDocument doc = kbDocumentService.renameDocument(docId, docName, getUsername());
        doc.setProductStatus(KbDocProductStatus.fromParseStatus(doc.getParseStatus()));
        kbDocumentService.sanitizeForUser(doc);
        return success(DesktopViews.document(doc));
    }

    /**
     * 批量重新处理
     */
    @Log(title = "知识库文档", businessType = BusinessType.UPDATE)
    @PostMapping("/batch-reprocess")
    public AjaxResult batchReprocess(@PathVariable Long kbId, @RequestBody Map<String, Object> body)
    {
        kbAuthorizationService.requireKb(kbId, KbAccessAction.WRITE);
        List<Long> ids = toLongList(body == null ? null : body.get("docIds"));
        if (ids.isEmpty())
        {
            return error("请选择文档");
        }
        kbAuthorizationService.requireDocsInKb(kbId, ids.toArray(Long[]::new), KbAccessAction.WRITE);
        int n = 0;
        for (Long id : ids)
        {
            kbDocumentService.reprocess(id);
            n++;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", n);
        return success(data);
    }

    private static List<Long> toLongList(Object raw)
    {
        List<Long> out = new ArrayList<>();
        if (!(raw instanceof List<?> list))
        {
            return out;
        }
        for (Object o : list)
        {
            if (o == null)
            {
                continue;
            }
            if (o instanceof Number n)
            {
                out.add(n.longValue());
            }
            else
            {
                try
                {
                    out.add(Long.parseLong(o.toString()));
                }
                catch (Exception ignored)
                {
                }
            }
        }
        return out;
    }

    /**
     * 删除知识库文档(软删 + 清 chunk/索引/IR/文件)。
     * 文件删除按 WRITE（EDITOR+），DELETE 仅用于知识库级危险动作。
     */
    @Log(title = "知识库文档", businessType = BusinessType.DELETE)
    @DeleteMapping("/{docIds}")
    public AjaxResult remove(@PathVariable Long kbId, @PathVariable Long[] docIds)
    {
        if (docIds == null || docIds.length == 0)
        {
            return toAjax(0);
        }
        // 逐个校验 doc 属于 path kbId，防止跨库删除
        kbAuthorizationService.requireDocsInKb(kbId, docIds, KbAccessAction.WRITE);
        return toAjax(kbDocumentService.deleteDocumentByIds(docIds));
    }

    /**
     * 受控文档下载：先 READ，再校验路径位于当前库目录。
     */
    @GetMapping("/{docId}/download")
    public void download(@PathVariable Long kbId, @PathVariable Long docId, HttpServletResponse response)
    {
        KbDocument doc = kbAuthorizationService.requireDocInKb(kbId, docId, KbAccessAction.READ);
        Path path = kbDocumentService.resolveReadableFile(doc);
        if (path == null)
        {
            throw new ServiceException("文件不可下载或不存在", HttpStatus.BAD_REQUEST);
        }
        try
        {
            String downloadName = StringUtils.isNotEmpty(doc.getDocName())
                ? doc.getDocName() : path.getFileName().toString();
            String contentType = Files.probeContentType(path);
            if (StringUtils.isEmpty(contentType))
            {
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }
            response.setContentType(contentType);
            FileUtils.setAttachmentResponseHeader(response, downloadName);
            FileUtils.writeBytes(path.toString(), response.getOutputStream());
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new ServiceException("下载失败: " + e.getMessage());
        }
    }

    /**
     * 获取文档详情（非管理员错误信息产品化，不返回堆栈）
     */
    @GetMapping(value = "/{docId}")
    public AjaxResult getInfo(@PathVariable Long kbId, @PathVariable Long docId)
    {
        KbDocument doc = kbAuthorizationService.requireDocInKb(kbId, docId, KbAccessAction.READ);
        doc.setProductStatus(KbDocProductStatus.fromParseStatus(doc.getParseStatus()));
        if (!isPlatformAdmin())
        {
            String product = KbDocProductError.message(doc);
            if (product != null)
            {
                doc.setErrorMsg(product);
            }
        }
        // 普通详情不暴露内部路径；诊断接口另开
        kbDocumentService.sanitizeForUser(doc);
        return success(doc);
    }

    /**
     * 产品预览：目录、安全 HTML/文本、质量摘要
     */
    @GetMapping("/{docId}/preview")
    public AjaxResult preview(@PathVariable Long kbId, @PathVariable Long docId)
    {
        kbAuthorizationService.requireDocInKb(kbId, docId, KbAccessAction.READ);
        return success(kbDocumentService.buildProductPreview(kbId, docId));
    }

    private static boolean isPlatformAdmin()
    {
        try
        {
            if (SecurityUtils.isAdmin())
            {
                return true;
            }
            return SecurityUtils.hasRole("admin");
        }
        catch (Exception e)
        {
            return false;
        }
    }
}
