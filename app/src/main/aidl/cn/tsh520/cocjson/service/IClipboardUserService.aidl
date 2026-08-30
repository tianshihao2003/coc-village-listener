package cn.tsh520.cocjson.service;

interface IClipboardUserService {
    /** 读取当前剪贴板文本；无内容或读取失败返回 null */
    String readClipboard();
    /** 逐模式详细诊断：每个候选的真实结果/报错 */
    String diagnose();
    /** 诊断信息：工作的参数模式编号 */
    String modeInfo();
    void destroy();
}
