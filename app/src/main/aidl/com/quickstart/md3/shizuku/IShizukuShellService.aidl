package com.quickstart.md3.shizuku;

interface IShizukuShellService {
    void destroy() = 16777114;
    String[] execArgs(in String[] args) = 1;
    String[] execShell(String cmd) = 2;
    String[] startShortcut(String packageName, String shortcutId, int userId) = 3;
    String[] startFreeform(String packageName, String activityName, int userId) = 4;
}
