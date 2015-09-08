package com.mycompany.cablemodemreader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.tidy.Tidy;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 *
 * @author bunchr
 */
public class App {

    private static final String urlStr = "http://192.168.100.1/RgEventLog.asp";

    public static void main(String[] args) {
        try {

            Connection conn = setup();

            URL url = new URL(urlStr);
            BufferedReader urlIn = new BufferedReader(
                    new InputStreamReader(url.openStream()));

            // intermediate destination
            StringWriter tidyWriter = new StringWriter();

            // 1st whack at cleaning sloppy HTML; stuff like cellpadding=0 should be ...="0"
            Tidy tidy = new Tidy(); // obtain a new Tidy instance
            tidy.setXHTML(false); // set desired config options using tidy setters 
            tidy.setWrapAttVals(false);
            tidy.setWraplen(2000);
            tidy.parse(urlIn, tidyWriter); // run tidy, providing an input and output stream

            // close up
            urlIn.close();

            // 2nd whack at fixing sloppy HTML (hard-wired stuff, so this is okay)
            // fix all the things that xml parser whines about, all closing tags
            StringBuilder cleanedHtmlSb = new StringBuilder();
            BufferedReader postTidyIn = new BufferedReader(new StringReader(tidyWriter.toString()));
            String line;
            while ((line = postTidyIn.readLine()) != null) {
                if (line.contains("<meta") || line.contains("<META")) {
                    line = line.replaceAll(">", "/>");
                }
                if (line.contains("<img border=\"0\" src=\"s_motorola.gif\">")) {
                    line = line.replaceAll("<img border=\"0\" src=\"s_motorola.gif\">", "<img border=\"0\" src=\"s_motorola.gif\" />");
                }
                if (line.contains("<img border=\"0\" src=\"about_surfboard.gif\">")) {
                    line = line.replaceAll("<img border=\"0\" src=\"about_surfboard.gif\">", "<img border=\"0\" src=\"about_surfboard.gif\" />");
                }
                if (line.contains("<br>")) {
                    line = line.replaceAll("<br>", "<br />");
                }
                cleanedHtmlSb.append(line).append("\n");
            }

            tidyWriter.close();
            postTidyIn.close();
//            System.out.println(cleanedHtmlSb.toString());

            // now try and do something w/ the DOM
            // ref http://stackoverflow.com/questions/2811001/how-to-read-xml-using-xpath-in-java
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setValidating(false);
            dbf.setNamespaceAware(true);
            dbf.setIgnoringComments(false);
            dbf.setIgnoringElementContentWhitespace(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document dom = db.parse(new InputSource(new StringReader(cleanedHtmlSb.toString())));

            XPathFactory xPathfactory = XPathFactory.newInstance();
            XPath xpath = xPathfactory.newXPath();

            // doesn't work... WHY?
            // XPathExpression expr = xpath.compile("//body/table[@id='AutoNumber1']/tbody/tr[3]/td[2]/table/tbody/tr");
            // works, more direct, but verbose
            // ref https://stackoverflow.com/questions/18241029/why-does-my-xpath-query-scraping-html-tables-only-work-in-firebug-but-not-the
            // XPathExpression expr = xpath.compile("//body/table[@id='AutoNumber1']/tr[3]/td[2]/table/tr");
            // works, more to the point (assuming no other TR's have that style applied!)
            // ref http://stackoverflow.com/questions/5931352/xpath-how-to-retrieve-the-value-of-a-table-cell-from-html-document
            XPathExpression expr = xpath.compile("//*/tr[@bgcolor='white']");

            // grunt thru the results
            NodeList nl = (NodeList) expr.evaluate(dom, XPathConstants.NODESET);

            System.out.println("\n\n-------------------- dump "
                    + nl.getLength()
                    + " nodes --------------------\n\n");

            for (int i = 0; i < nl.getLength(); i++) {
                // at this pt, node is 1 of 100 tr's
                Node trNode = nl.item(i);

                // now get the underlying td's
                StringBuilder trSb = new StringBuilder();
                Element e = (Element) trNode;
                NodeList tdList = e.getElementsByTagName("td");
                for (int j = 0; j < tdList.getLength(); j++) {
                    Node tdNode = tdList.item(j);
                    trSb.append(tdNode.getTextContent());
                    if (j != 2) {
                        trSb.append("|");
                    }
                }

                // figure I'll use md5 of the <tr> content as the key
                // ref http://stackoverflow.com/questions/415953/how-can-i-generate-an-md5-hash
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] originalBytes = trSb.toString().getBytes("UTF-8");
                byte[] hashBytes = md.digest(originalBytes);
                BigInteger bigInt = new BigInteger(1, hashBytes);
                String hash = bigInt.toString(16);

                // md5 ~= `echo -n "${dtg}|${pri}|${desc}"`
                System.out.println(hash + " :: " + trSb.toString());

                // chop up into vo
                String[] parts = trSb.toString().split("\\|");
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                Date dtg = sdf.parse(parts[0]);
                LogVO vo = new LogVO(hash, dtg, parts[1], parts[2]);

                // persist anything new
                boolean found = find(vo, conn);
                if (!found) {
                    save(vo, conn);
                }
            }
        } catch (IOException | ParserConfigurationException | SAXException | XPathExpressionException | ParseException | NoSuchAlgorithmException ex) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public static Connection setup() {
        Connection connection = null;
        try {
            Class.forName("oracle.jdbc.OracleDriver");

            connection = DriverManager.getConnection(
                    "jdbc:oracle:thin:@localhost:1521:xe", "monitor", "version3");
//            connection.close();
        } catch (ClassNotFoundException ex) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        } catch (SQLException ex) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        }
        return connection;
    }

    public static boolean find(LogVO vo, Connection conn) {
        LogVO found = new LogVO();
        try {
            String sql = "select md5, dtg, priority, description from log where md5 = ?";
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, vo.getMd5());
            try (ResultSet rset = pstmt.executeQuery()) {
                if (rset.next()) {
                    found.setMd5(rset.getString("md5"));
                    found.setDtg(rset.getDate("dtg"));
                    found.setPriority(rset.getString("priority"));
                    found.setDescription(rset.getString("description"));
                }
            }
        } catch (SQLException ex) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        }
        return !(null == found.md5 || found.md5.isEmpty());
    }

    public static void save(LogVO vo, Connection conn) {
        try {
            String sql = "insert into log(md5,dtg,priority,description) values (?,?,?,?)";
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, vo.getMd5());
            // TODO use setTimestamp, verify I get date AND time
            pstmt.setTimestamp(2, new Timestamp(vo.getDtg().getTime()));
            pstmt.setString(3, vo.getPriority());
            pstmt.setString(4, vo.getDescription());
            pstmt.executeQuery();
        } catch (SQLException ex) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private static class LogVO {

        private String md5;
        private Date dtg;

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 31 * hash + Objects.hashCode(this.md5);
            hash = 31 * hash + Objects.hashCode(this.dtg);
            hash = 31 * hash + Objects.hashCode(this.priority);
            hash = 31 * hash + Objects.hashCode(this.description);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final LogVO other = (LogVO) obj;
            if (!Objects.equals(this.md5, other.md5)) {
                return false;
            }
            if (!Objects.equals(this.dtg, other.dtg)) {
                return false;
            }
            if (!Objects.equals(this.priority, other.priority)) {
                return false;
            }
            if (!Objects.equals(this.description, other.description)) {
                return false;
            }
            return true;
        }
        private String priority;
        private String description;

        public LogVO() {
        }

        public LogVO(String md5, Date dtg, String priority, String description) {
            this.md5 = md5;
            this.dtg = dtg;
            this.priority = priority;
            this.description = description;
        }

        public String getMd5() {
            return md5;
        }

        public void setMd5(String md5) {
            this.md5 = md5;
        }

        public Date getDtg() {
            return dtg;
        }

        public void setDtg(Date dtg) {
            this.dtg = dtg;
        }

        public String getPriority() {
            return priority;
        }

        public void setPriority(String priority) {
            this.priority = priority;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

}
