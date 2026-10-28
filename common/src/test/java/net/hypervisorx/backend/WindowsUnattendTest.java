package net.hypervisorx.backend;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsUnattendTest {
    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void biosWindows10IsWellFormedAndInstallsToPartition1() throws Exception {
        String xml = WindowsUnattend.xml(false, false);
        Document d = parse(xml);
        assertEquals("unattend", d.getDocumentElement().getLocalName());
        assertTrue(xml.contains("<Value>Windows 10 Pro</Value>"));
        assertTrue(xml.contains("<PartitionID>1</PartitionID></InstallTo>"));
        assertFalse(xml.contains("LabConfig"));
        assertTrue(xml.contains("%d:\\setup64.exe"), "cmd loop variable must be a single %");
    }

    @Test
    void efiWindows11SkipsHardwareChecksAndUsesGpt() throws Exception {
        String xml = WindowsUnattend.xml(true, true);
        parse(xml);
        assertTrue(xml.contains("<Type>EFI</Type>"));
        assertTrue(xml.contains("<PartitionID>3</PartitionID></InstallTo>"));
        assertTrue(xml.contains("BypassTPMCheck") && xml.contains("BypassSecureBootCheck"));
        assertTrue(xml.contains("<RunSynchronousCommand wcm:action=\"add\"><Order>1</Order><Path>reg add HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\OOBE"));
    }
}
