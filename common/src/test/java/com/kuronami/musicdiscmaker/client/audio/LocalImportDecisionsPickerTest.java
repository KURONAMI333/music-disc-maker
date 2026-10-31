package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Linux で GUI セッションが無い時だけ、押す前に理由を出す。 */
class LocalImportDecisionsPickerTest {

    @Test
    void linuxWithoutDisplayIsUnavailable() {
        assertTrue(LocalImportDecisions.pickerSessionUnavailable("Linux", null, null));
        assertTrue(LocalImportDecisions.pickerSessionUnavailable("Linux", "", "  "));
    }

    /** Wayland だけでは TinyFileDialogs の GUI は開かない (以前は false にしていた)。 */
    @Test
    void waylandAloneIsStillUnavailable() {
        assertTrue(LocalImportDecisions.pickerSessionUnavailable("Linux", null, "wayland-0"));
    }

    @Test
    void linuxWithDisplayIsUsableWithoutBackendCheck() {
        assertFalse(LocalImportDecisions.pickerSessionUnavailable("Linux", ":0", null));
        assertFalse(LocalImportDecisions.pickerSessionUnavailable("Linux", ":0", "wayland-0"));
    }

    @Test
    void linuxWithDisplayButNoBackendIsUnavailable() {
        assertTrue(LocalImportDecisions.pickerSessionUnavailable(
                "Linux", ":0", null, name -> false));
    }

    @Test
    void linuxWithDisplayAndBackendIsUsable() {
        assertFalse(LocalImportDecisions.pickerSessionUnavailable(
                "Linux", ":0", "wayland-0", name -> name.equals("zenity")));
    }

    /** python3 が PATH にあるだけでは tkinter backend と認めない (開けず取消相当になる)。 */
    @Test
    void onlyPythonIsNotABackend() {
        assertTrue(LocalImportDecisions.pickerSessionUnavailable(
                "Linux", ":0", null, name -> name.equals("python3") || name.equals("python")));
    }

    @Test
    void backendNameIsReportedWithoutPaths() {
        assertEquals("kdialog", LocalImportDecisions.firstDialogBackendOnPath(
                name -> name.equals("kdialog") || name.equals("python3")));
        assertNull(LocalImportDecisions.firstDialogBackendOnPath(name -> false));
    }

    @Test
    void otherOperatingSystemsAreNotChecked() {
        assertFalse(LocalImportDecisions.pickerSessionUnavailable("Mac OS X", null, null));
        assertFalse(LocalImportDecisions.pickerSessionUnavailable("Windows 11", null, null));
        assertFalse(LocalImportDecisions.pickerSessionUnavailable(null, null, null));
    }

    /**
     * PATH 上の同名ディレクトリを実行ファイルと数えない。zenity という名前のディレクトリが
     * あるだけで Linux の選択ダイアログが開けると誤認していた。
     */
    @Test
    void directoriesOnPathAreNotExecutableFiles(@TempDir Path directory) throws IOException {
        final Path dirAsBackend = Files.createDirectory(directory.resolve("zenity"));
        final Path fileAsBackend = Files.createFile(directory.resolve("kdialog"));
        assumeTrue(dirAsBackend.toFile().setExecutable(true), "cannot mark a directory executable");
        assumeTrue(fileAsBackend.toFile().setExecutable(true), "cannot mark a file executable");

        assertFalse(LocalImportDecisions.isExecutableOnPath("zenity", directory.toString()),
                "a directory named like a backend must not count as an executable");
        assertTrue(LocalImportDecisions.isExecutableOnPath("kdialog", directory.toString()));
        assertFalse(LocalImportDecisions.isExecutableOnPath("missing", directory.toString()));
    }
}
