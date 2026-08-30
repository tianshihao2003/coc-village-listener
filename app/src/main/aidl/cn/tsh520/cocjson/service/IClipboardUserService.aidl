package cn.tsh520.cocjson.service;

interface IClipboardUserService {
    /** 读取当前剪贴板全文；无内容、读取失败返回 null（Android 16 上服务端可能拒绝非焦点读取） */
    String readClipboard();
    /** 轻量变化检测：返回剪贴板元信息指纹；无法读取返回 "NO_DESC" */
    String detectChange();
    /** 系统剪贴板变化回调计数（需 listener 注册成功）；-1 = 未注册 */
    int clipChangeCount();
    /** 逐模式详细诊断：每个候选的真实结果/报错 */
    String diagnose();
    /** 诊断信息：工作的参数模式编号 */
    String modeInfo();
    void destroy();
}
