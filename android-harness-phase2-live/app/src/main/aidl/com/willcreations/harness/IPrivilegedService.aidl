package com.willcreations.harness;

interface IPrivilegedService {
    String identity() = 1;
    String listPackages(int limit) = 2;
    String listProcesses(int limit) = 3;
    String readLogcat(int lines) = 4;
    void destroy() = 16777114;
}
