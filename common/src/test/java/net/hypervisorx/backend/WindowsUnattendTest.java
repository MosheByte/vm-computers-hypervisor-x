package net.hypervisorx.backend;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsUnattendTest {
    private static final GuestTools.Disc VMWARE = new GuestTools.Disc(Path.of("windows.iso"), "setup64.exe",
            "start \"\" /wait %d:\\setup64.exe /S /v\"/qn REBOOT=R ADDLOCAL=ALL\"");
    private static final GuestTools.Disc VBOX = new GuestTools.Disc(Path.of("VBoxGuestAdditions.iso"), "VBoxWindowsAdditions.exe",
            "(%d:\\cert\\VBoxCertUtil.exe add-trusted-publisher %d:\\cert\\vbox*.cer & start \"\" /wait %d:\\VBoxWindowsAdditions.exe /S)");

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void biosWindows10IsWellFormedAndInstallsToPartition1() throws Exception {
        String xml = WindowsUnattend.xml(false, false, VMWARE.windowsCommand());
        Document d = parse(xml);
        assertEquals("unattend", d.getDocumentElement().getLocalName());
        assertTrue(xml.contains("<Value>Windows 10 Pro</Value>"));
        assertTrue(xml.contains("<PartitionID>1</PartitionID></InstallTo>"));
        assertFalse(xml.contains("LabConfig"));
        assertTrue(xml.contains("%d:\\setup64.exe"), "cmd loop variable must be a single %");
        assertTrue(xml.contains("<Order>3</Order><CommandLine>shutdown /r"), "restarts after the tools");
    }

    @Test
    void efiWindows11SkipsHardwareChecksAndUsesGpt() throws Exception {
        String xml = WindowsUnattend.xml(true, true, null);
        parse(xml);
        assertTrue(xml.contains("<Type>EFI</Type>"));
        assertTrue(xml.contains("<PartitionID>3</PartitionID></InstallTo>"));
        assertTrue(xml.contains("BypassTPMCheck") && xml.contains("BypassSecureBootCheck"));
        assertTrue(xml.contains("<RunSynchronousCommand wcm:action=\"add\"><Order>1</Order><Path>reg add HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\OOBE"));
    }

    @Test
    void guestAdditionsCommandSurvivesXmlAndToolsCanBeLeftOut() throws Exception {
        String xml = WindowsUnattend.xml(true, true, VBOX.windowsCommand());
        Document d = parse(xml);
        String cmd = d.getElementsByTagNameNS("*", "CommandLine").item(1).getTextContent();
        assertEquals(VBOX.windowsCommand(), cmd);
        assertTrue(cmd.startsWith("cmd /c for %d in (D E F G H I J K) do if exist %d:\\VBoxWindowsAdditions.exe ("));

        String none = WindowsUnattend.xml(true, true, null);
        parse(none);
        assertFalse(none.contains("guest tools"));
        assertFalse(none.contains("@TOOLS@"));
    }
}
