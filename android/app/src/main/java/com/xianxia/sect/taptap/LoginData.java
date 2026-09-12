package com.xianxia.sect.taptap;

public class LoginData {
    private final String openid;
    private final String unionid;
    private final String name;
    private final String avatar;
    private final String kid;
    private final String tokenType;
    private final String macKey;
    private final String macAlgorithm;

    public LoginData(String openid, String unionid, String name, String avatar, 
                     String kid, String tokenType, String macKey, String macAlgorithm) {
        this.openid = openid;
        this.unionid = unionid;
        this.name = name;
        this.avatar = avatar;
        this.kid = kid;
        this.tokenType = tokenType;
        this.macKey = macKey;
        this.macAlgorithm = macAlgorithm;
    }

    public String getOpenid() { return openid; }
    public String getUnionid() { return unionid; }
    public String getName() { return name; }
    public String getAvatar() { return avatar; }
    public String getKid() { return kid; }
    public String getTokenType() { return tokenType; }
    public String getMacKey() { return macKey; }
    public String getMacAlgorithm() { return macAlgorithm; }
}
