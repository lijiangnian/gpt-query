package com.example.mediaparser.subtitle;

/** Credential/resource preflight. It never submits real audio and reports ambiguous results honestly. */
public final class DoubaoCredentialValidator {
    private DoubaoCredentialValidator(){}
    public static GeminiKeyValidator.Result validate(DoubaoCredentialStore.Credentials c){
        try{DoubaoTranscriber.Response r=DoubaoTranscriber.probe(c);String d=(r.status.isBlank()?"HTTP "+r.http:r.status)+(r.message.isBlank()?"":" · "+r.message)+(r.logId.isBlank()?"":" · LogID "+r.logId);
            if("20000000".equals(r.status))return GeminiKeyValidator.Result.ok("豆包鉴权通过 · 空音频探测成功");
            String lower=(r.message+" "+r.body.toString()).toLowerCase(java.util.Locale.ROOT);
            if("45000030".equals(r.status)||lower.contains("requested resource not granted")||lower.contains("resource")&&(lower.contains("not")||lower.contains("invalid")))return GeminiKeyValidator.Result.invalid("豆包凭证已送达，但当前凭证未获授权 "+c.resourceId+"，请确认凭证类型与服务实例一致 · "+d);
            if(lower.contains("unauthor")||lower.contains("invalid token")||lower.contains("access denied")||r.http==401||r.http==403)return GeminiKeyValidator.Result.invalid("豆包鉴权失败 · "+d);
            if("45000001".equals(r.status)||"45000002".equals(r.status)||"45000151".equals(r.status)||r.http>=200&&r.http<500&&(lower.contains("audio")||lower.contains("empty")||lower.contains("parameter")||lower.contains("空音频")||lower.contains("参数")))return GeminiKeyValidator.Result.ok("服务可达，凭证已通过到音频参数校验阶段 · 实际音频任务仍需验证 · "+d);
            return GeminiKeyValidator.Result.error("无法确认鉴权，请查看实际返回 · "+d);
        }catch(Exception e){return GeminiKeyValidator.Result.network("豆包连接测试失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
    }
}
