package cn.tsh520.cocjson.service;

interface IClipboardUserService {
    /** 读取当前剪贴板文本；无内容或读取失败返回 null */
    String readClipboard();
    /** 诊断信息：工作的参数模式编号 */
    String modeInfo();
    void destroy();
}
