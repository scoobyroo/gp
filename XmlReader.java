import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * JDK-only parser for the XML table exports: {@code <query><row><COL>text|CDATA</COL>...</row>...</query>}.
 * Each {@code <row>} becomes a {@link Row}; each child element becomes a column. Text and CDATA are returned
 * verbatim; an empty element becomes null.
 */
final class XmlReader {
    private XmlReader() {
    }

    /** @throws XmlFormatException when the file cannot be read or is not well-formed (names the file) */
    static List<Row> read(File file) {
        String name = file.getName();
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);              // merge CDATA + text
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);               // no DTD / entity resolution
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        XMLStreamReader r = null;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            r = f.createXMLStreamReader(in);
            List<Row> rows = new ArrayList<>();
            Row current = null;
            String col = null;
            StringBuilder text = null;
            while (r.hasNext()) {
                switch (r.next()) {
                    case XMLStreamConstants.START_ELEMENT:
                        if (current == null) {
                            if ("row".equals(r.getLocalName())) {
                                current = new Row();
                            }
                        } else if (col == null) {
                            col = r.getLocalName();
                            text = new StringBuilder();
                        }
                        break;
                    case XMLStreamConstants.CHARACTERS:
                    case XMLStreamConstants.CDATA:
                    case XMLStreamConstants.SPACE:
                        if (text != null) {
                            text.append(r.getText());
                        }
                        break;
                    case XMLStreamConstants.END_ELEMENT:
                        if (col != null) {
                            String v = text.toString();
                            current.put(col, v.isEmpty() ? null : v);
                            col = null;
                            text = null;
                        } else if (current != null) {
                            rows.add(current);
                            current = null;
                        }
                        break;
                    default:
                        break;
                }
            }
            return rows;
        } catch (IOException | XMLStreamException e) {
            throw new XmlFormatException(name, e.getMessage(), e);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (XMLStreamException ignored) {
                    // nothing useful to do
                }
            }
        }
    }
}
