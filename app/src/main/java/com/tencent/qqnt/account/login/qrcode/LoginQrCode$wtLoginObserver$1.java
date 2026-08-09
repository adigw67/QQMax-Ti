package com.tencent.qqnt.account.login.qrcode;

import com.tencent.qqnt.account.wtlogin.QrWtLoginExtObserver;

/**
 * Compile-only stub for the anonymous QR-login poll observer created inside
 * {@code LoginQrCode.<init>} (field {@code i}, the {@code wtLoginObserver}). Its {@code d(...)} is the
 * queryCodeResult callback: ret 0 = confirmed (account known → callback.e), ret 53 = SCANNED (account
 * is in scope but the native code drops it before calling callback.Q), other = error.
 *
 * Declared with a default no-arg constructor (the real class's ctor takes the outer LoginQrCode, which
 * we can't supply) so a @Mixin can extend it as {@code : `LoginQrCode$wtLoginObserver$1`()}. ApkMixin
 * copies the @Mixin's overridden {@code d} into the real class at patch time; this stub is never run.
 * Hooked by LoginScanObserver to capture the scanned uin so the login screen can show identity early.
 */
public class LoginQrCode$wtLoginObserver$1 extends QrWtLoginExtObserver {
    @Override
    public void d(String account, int accountType, long sigCreateTime, int ret, String errMsg) {
    }
}
