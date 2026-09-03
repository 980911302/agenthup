package com.ruoyi.system.kb.parser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * XML 结构解析。禁用外部实体,按叶子元素保留 XML 路径和属性。
 */
@Component
public class XmlParser implements KbParser
{
    @Override
    public boolean supports(String extension)
    {
        return "xml".equalsIgnoreCase(extension);
    }

    @Override
    public IrDoc parse(File file, String fileName) throws Exception
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        Element root = factory.newDocumentBuilder().parse(file).getDocumentElement();
        IrDoc doc = ParserSupport.newDocument(fileName, "xml");
        int[] pos = {0};
        walk(doc, root, new ArrayList<>(), "/" + root.getTagName(), pos);
        return doc;
    }

    private void walk(IrDoc doc, Element element, List<String> parentPath,
                      String sourcePath, int[] pos)
    {
        List<String> headingPath = new ArrayList<>(parentPath);
        headingPath.add(element.getTagName());
        List<Element> children = childElements(element);
        String directText = directText(element);
        if (!directText.isEmpty() || children.isEmpty() || element.hasAttributes())
        {
            StringBuilder content = new StringBuilder();
            NamedNodeMap attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++)
            {
                Node attr = attributes.item(i);
                if (content.length() > 0)
                {
                    content.append(" | ");
                }
                content.append('@').append(attr.getNodeName()).append(": ").append(attr.getNodeValue());
            }
            if (!directText.isEmpty())
            {
                if (content.length() > 0)
                {
                    content.append(" | ");
                }
                content.append(directText);
            }
            ParserSupport.addBlock(doc, pos[0]++, content.toString(), "xml_element",
                headingPath, null, sourcePath);
        }
        for (Element child : children)
        {
            walk(doc, child, headingPath, sourcePath + "/" + child.getTagName(), pos);
        }
    }

    private static List<Element> childElements(Element element)
    {
        List<Element> children = new ArrayList<>();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++)
        {
            if (nodes.item(i) instanceof Element child)
            {
                children.add(child);
            }
        }
        return children;
    }

    private static String directText(Element element)
    {
        StringBuilder text = new StringBuilder();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++)
        {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
            {
                text.append(node.getNodeValue()).append(' ');
            }
        }
        return ParserSupport.normalize(text.toString());
    }
}
