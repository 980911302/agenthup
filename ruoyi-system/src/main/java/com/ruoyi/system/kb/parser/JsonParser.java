package com.ruoyi.system.kb.parser;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * JSON 结构解析。按顶层字段/数组元素生成块,保留 JSON 路径用于后续引用。
 */
@Component
public class JsonParser implements KbParser
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public boolean supports(String extension)
    {
        return "json".equalsIgnoreCase(extension);
    }

    @Override
    public IrDoc parse(File file, String fileName) throws Exception
    {
        JsonNode root = MAPPER.readTree(file);
        IrDoc doc = ParserSupport.newDocument(fileName, "json");
        int[] pos = {0};
        appendNode(doc, root, "$", new ArrayList<>(), pos, true);
        return doc;
    }

    private void appendNode(IrDoc doc, JsonNode node, String path,
                            List<String> headingPath, int[] pos, boolean root) throws Exception
    {
        if (node == null || node.isNull())
        {
            return;
        }
        if (node.isObject())
        {
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext())
            {
                String fieldName = fields.next();
                String childPath = path + "." + fieldName;
                List<String> childHeading = new ArrayList<>(headingPath);
                childHeading.add(fieldName);
                JsonNode value = node.get(fieldName);
                if (value.isContainerNode() && containerSize(value) > 1)
                {
                    ParserSupport.addHeading(doc, pos[0]++, fieldName,
                        Math.min(6, childHeading.size()), childHeading, null, childPath);
                    appendNode(doc, value, childPath, childHeading, pos, false);
                }
                else
                {
                    addJsonBlock(doc, fieldName, value, childPath, childHeading, pos);
                }
            }
        }
        else if (node.isArray())
        {
            for (int i = 0; i < node.size(); i++)
            {
                JsonNode item = node.get(i);
                String childPath = path + "[" + i + "]";
                List<String> childHeading = new ArrayList<>(headingPath);
                childHeading.add("元素 " + (i + 1));
                if (item.isContainerNode())
                {
                    addJsonBlock(doc, null, item, childPath, childHeading, pos);
                }
                else
                {
                    ParserSupport.addBlock(doc, pos[0]++, item.asText(), "json_value",
                        childHeading, null, childPath);
                }
            }
        }
        else if (root)
        {
            ParserSupport.addBlock(doc, pos[0]++, node.asText(), "json_value",
                headingPath, null, path);
        }
    }

    private void addJsonBlock(IrDoc doc, String name, JsonNode value, String path,
                              List<String> headingPath, int[] pos) throws Exception
    {
        String rendered = value.isValueNode() ? value.asText() : MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(value);
        String text = name != null ? name + ": " + rendered : rendered;
        ParserSupport.addBlock(doc, pos[0]++, text, "json_object", headingPath, null, path);
    }

    private static int containerSize(JsonNode node)
    {
        return node.isContainerNode() ? node.size() : 0;
    }
}
