package com.comingx.zanao.utils

import java.util.SortedMap

/**
 * JNI bridge to the campus market app's libAesNew.so.
 *
 * The native library resolves these symbols dynamically:
 *   Java_com_comingx_zanao_utils_JniUtils_makeSign
 *   Java_com_comingx_zanao_utils_JniUtils_unhashAppResult
 *   Java_com_comingx_zanao_utils_JniUtils_hashText
 *   Java_com_comingx_zanao_utils_JniUtils_hash
 *
 * The class name and method descriptors must stay exactly as declared here.
 * Call [ink.zanao.campusreply.ZanaoNative.ensure] before the first invocation.
 */
object JniUtils {
    external fun makeSign(map: SortedMap<String, Any?>): String?
    external fun unhashAppResult(input: String): String?
    external fun hashText(input: String, key: String): String?
    external fun hash(input: String): String?
}
