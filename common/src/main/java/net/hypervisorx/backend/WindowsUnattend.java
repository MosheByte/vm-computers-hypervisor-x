package net.hypervisorx.backend;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.util.Processes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Hands-free Windows setup for a brand-new Windows VM: an {@code Autounattend.xml} on a small
 * answer disc that Windows Setup reads by itself. It partitions the new virtual disk, installs
 * Windows (Pro edition, generic installation key, so Windows stays unactivated until the player
 * enters their own key), creates a local "Player" account that signs in automatically, skips the
 * first-run screens and installs the hypervisor's guest tools from a second disc. Windows 11's
 * TPM / Secure Boot / RAM checks are skipped, because the virtual machine has no TPM.
 * <p>
 * The answer disc only goes in on the very first boot of a fresh disk. That way booting the
 * Windows ISO again later can't wipe an installed system.
 */
public final class WindowsUnattend {
    /** Microsoft's generic Windows 10/11 Pro install key. It installs, it doesn't activate. */
    private static final String GENERIC_PRO_KEY = "VK7JG-NPHTM-C97JM-9MPGT-3V66T";

    private WindowsUnattend() {
    }

    /** Does a fresh VM of this guest type get an answer disc? */
    public static boolean applies(GuestOs os) {
        return os == GuestOs.WINDOWS_10 || os == GuestOs.WINDOWS_11;
    }

    /**
     * Builds {@code unattend.iso} in the VM folder with {@code mkisofs} and returns it, or null when
     * no ISO tool is available (setup then runs interactively, as before).
     */
    public static Path buildIso(VmLaunchSpec spec, Path mkisofs) {
        if (mkisofs == null || !Files.isRegularFile(mkisofs)) return null;
        try {
            Path dir = spec.vmDir().resolve("unattend");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("Autounattend.xml"), xml(spec.efi(), spec.guestOs() == GuestOs.WINDOWS_11), StandardCharsets.UTF_8);
            Path iso = spec.vmDir().resolve("unattend.iso");
            Processes.Result r = Processes.run(60, mkisofs.toString(), "-quiet", "-J", "-r", "-V", "UNATTEND", "-o", iso.toString(), dir.toString());
            if (!r.ok() || !Files.isRegularFile(iso)) {
                HyperVisorX.LOG.warn("Could not build the Windows answer disc: {}", r.output());
                return null;
            }
            return iso;
        } catch (IOException e) {
            HyperVisorX.LOG.warn("Could not build the Windows answer disc: {}", e.getMessage());
            return null;
        }
    }

    static String xml(boolean efi, boolean windows11) {
        String component = "processorArchitecture=\"amd64\" publicKeyToken=\"31bf3856ad364e35\" language=\"neutral\" versionScope=\"nonSxS\"";
        String partitions = efi
                ? """
                          <CreatePartitions>
                            <CreatePartition wcm:action="add"><Order>1</Order><Type>EFI</Type><Size>300</Size></CreatePartition>
                            <CreatePartition wcm:action="add"><Order>2</Order><Type>MSR</Type><Size>16</Size></CreatePartition>
                            <CreatePartition wcm:action="add"><Order>3</Order><Type>Primary</Type><Extend>true</Extend></CreatePartition>
                          </CreatePartitions>
                          <ModifyPartitions>
                            <ModifyPartition wcm:action="add"><Order>1</Order><PartitionID>1</PartitionID><Format>FAT32</Format><Label>System</Label></ModifyPartition>
                            <ModifyPartition wcm:action="add"><Order>2</Order><PartitionID>2</PartitionID></ModifyPartition>
                            <ModifyPartition wcm:action="add"><Order>3</Order><PartitionID>3</PartitionID><Format>NTFS</Format><Label>Windows</Label><Letter>C</Letter></ModifyPartition>
                          </ModifyPartitions>"""
                : """
                          <CreatePartitions>
                            <CreatePartition wcm:action="add"><Order>1</Order><Type>Primary</Type><Extend>true</Extend></CreatePartition>
                          </CreatePartitions>
                          <ModifyPartitions>
                            <ModifyPartition wcm:action="add"><Order>1</Order><PartitionID>1</PartitionID><Format>NTFS</Format><Label>Windows</Label><Letter>C</Letter><Active>true</Active></ModifyPartition>
                          </ModifyPartitions>""";
        String bypass = windows11 ? """
                      <RunSynchronous>
                        <RunSynchronousCommand wcm:action="add"><Order>1</Order><Path>reg add HKLM\\SYSTEM\\Setup\\LabConfig /v BypassTPMCheck /t REG_DWORD /d 1 /f</Path></RunSynchronousCommand>
                        <RunSynchronousCommand wcm:action="add"><Order>2</Order><Path>reg add HKLM\\SYSTEM\\Setup\\LabConfig /v BypassSecureBootCheck /t REG_DWORD /d 1 /f</Path></RunSynchronousCommand>
                        <RunSynchronousCommand wcm:action="add"><Order>3</Order><Path>reg add HKLM\\SYSTEM\\Setup\\LabConfig /v BypassRAMCheck /t REG_DWORD /d 1 /f</Path></RunSynchronousCommand>
                        <RunSynchronousCommand wcm:action="add"><Order>4</Order><Path>reg add HKLM\\SYSTEM\\Setup\\LabConfig /v BypassCPUCheck /t REG_DWORD /d 1 /f</Path></RunSynchronousCommand>
                      </RunSynchronous>""" : "";
        String noNetworkOobe = windows11 ? """
                        <SynchronousCommand wcm:action="add"><Order>1</Order><CommandLine>reg add HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\OOBE /v BypassNRO /t REG_DWORD /d 1 /f</CommandLine></SynchronousCommand>""" : "";
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <!-- Written by HyperVisor X: unattended setup of a new virtual machine. -->
                <unattend xmlns="urn:schemas-microsoft-com:unattend" xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">
                  <settings pass="windowsPE">
                    <component name="Microsoft-Windows-International-Core-WinPE" %1$s>
                      <SetupUILanguage><UILanguage>en-US</UILanguage></SetupUILanguage>
                      <InputLocale>en-US</InputLocale>
                      <SystemLocale>en-US</SystemLocale>
                      <UILanguage>en-US</UILanguage>
                      <UserLocale>en-US</UserLocale>
                    </component>
                    <component name="Microsoft-Windows-Setup" %1$s>
                %2$s
                      <DiskConfiguration>
                        <Disk wcm:action="add">
                          <DiskID>0</DiskID>
                          <WillWipeDisk>true</WillWipeDisk>
                %3$s
                        </Disk>
                      </DiskConfiguration>
                      <ImageInstall>
                        <OSImage>
                          <InstallFrom><MetaData wcm:action="add"><Key>/IMAGE/NAME</Key><Value>%4$s Pro</Value></MetaData></InstallFrom>
                          <InstallTo><DiskID>0</DiskID><PartitionID>%5$d</PartitionID></InstallTo>
                        </OSImage>
                      </ImageInstall>
                      <UserData>
                        <AcceptEula>true</AcceptEula>
                        <FullName>Player</FullName>
                        <Organization>HyperVisor X</Organization>
                        <ProductKey><Key>%6$s</Key><WillShowUI>OnError</WillShowUI></ProductKey>
                      </UserData>
                    </component>
                  </settings>
                  <settings pass="specialize">
                    <component name="Microsoft-Windows-Shell-Setup" %1$s>
                      <ComputerName>HVX-PC</ComputerName>
                    </component>
                    <component name="Microsoft-Windows-Deployment" %1$s>
                      <RunSynchronous>
                %7$s
                      </RunSynchronous>
                    </component>
                  </settings>
                  <settings pass="oobeSystem">
                    <component name="Microsoft-Windows-International-Core" %1$s>
                      <InputLocale>en-US</InputLocale>
                      <SystemLocale>en-US</SystemLocale>
                      <UILanguage>en-US</UILanguage>
                      <UserLocale>en-US</UserLocale>
                    </component>
                    <component name="Microsoft-Windows-Shell-Setup" %1$s>
                      <OOBE>
                        <HideEULAPage>true</HideEULAPage>
                        <HideOEMRegistrationScreen>true</HideOEMRegistrationScreen>
                        <HideOnlineAccountScreens>true</HideOnlineAccountScreens>
                        <HideWirelessSetupInOOBE>true</HideWirelessSetupInOOBE>
                        <ProtectYourPC>3</ProtectYourPC>
                      </OOBE>
                      <UserAccounts>
                        <LocalAccounts>
                          <LocalAccount wcm:action="add">
                            <Name>Player</Name>
                            <DisplayName>Player</DisplayName>
                            <Group>Administrators</Group>
                            <Password><Value></Value><PlainText>true</PlainText></Password>
                          </LocalAccount>
                        </LocalAccounts>
                      </UserAccounts>
                      <AutoLogon>
                        <Enabled>true</Enabled>
                        <Username>Player</Username>
                        <Password><Value></Value><PlainText>true</PlainText></Password>
                        <LogonCount>9999999</LogonCount>
                      </AutoLogon>
                      <FirstLogonCommands>
                        <SynchronousCommand wcm:action="add"><Order>1</Order><CommandLine>cmd /c powercfg /change monitor-timeout-ac 0 &amp; powercfg /change standby-timeout-ac 0 &amp; powercfg /change hibernate-timeout-ac 0</CommandLine></SynchronousCommand>
                        <SynchronousCommand wcm:action="add"><Order>2</Order><CommandLine>cmd /c for %%d in (D E F G H I J) do if exist %%d:\\setup64.exe start "" /wait %%d:\\setup64.exe /S /v"/qn REBOOT=R ADDLOCAL=ALL"</CommandLine><Description>Install VMware Tools</Description></SynchronousCommand>
                        <SynchronousCommand wcm:action="add"><Order>3</Order><CommandLine>shutdown /r /t 15 /c "HyperVisor X: guest tools installed, restarting"</CommandLine></SynchronousCommand>
                      </FirstLogonCommands>
                    </component>
                  </settings>
                </unattend>
                """.formatted(component, bypass, partitions, windows11 ? "Windows 11" : "Windows 10", efi ? 3 : 1, GENERIC_PRO_KEY,
                noNetworkOobe.isEmpty() ? "<RunSynchronousCommand wcm:action=\"add\"><Order>1</Order><Path>cmd /c exit 0</Path></RunSynchronousCommand>"
                        : noNetworkOobe.replace("SynchronousCommand", "RunSynchronousCommand").replace("<CommandLine>", "<Path>").replace("</CommandLine>", "</Path>"));
    }
}
