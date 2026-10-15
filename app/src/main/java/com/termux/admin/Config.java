// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin;

/** The build must pin the certificate used to sign this APK. */
final class Config {
    static final String HOST_PACKAGE = "com.termux";
    static final String CLIENT_PACKAGE = "com.termux";
    static final String CLI_PACKAGE = "com.termux";
    static final String HOST_CERT = com.termux.BuildConfig.ADMIN_CERT_SHA256;
    static final String CLIENT_CERT = HOST_CERT;
}
