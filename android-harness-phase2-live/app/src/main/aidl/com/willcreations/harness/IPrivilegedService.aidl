package com.willcreations.harness;

interface IPrivilegedService {
    String identity();
    String listPackages(int limit);
    String listProcesses(int limit);
    String readLogcat(int lines);
    void destroy() = 16777114;
}
