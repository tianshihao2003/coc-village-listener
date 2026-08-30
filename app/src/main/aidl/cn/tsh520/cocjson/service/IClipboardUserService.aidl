package cn.tsh520.cocjson.service;

interface IClipboardUserService {
    /** 读取当前剪贴板全文；无内容、超1MB或读取失败返回 null */
    String readClipboard();
    /** 轻量变化检测：返回剪贴板元信息指纹；无法读取返回 "NO_DESC" */
    String detectChange();
    /** 逐模式详细诊断：每个候选的真实结果/报错 */
    String diagnose();
    /** 诊断信息：工作的参数模式编号 */
    String modeInfo();
    void destroy();
}
