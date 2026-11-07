package com.streamflixreborn.streamflix.utils

import okhttp3.Dns

/**
 * Secure shim: the upstream DoH helper disabled certificate validation.
 * This port keeps the system resolver and never trusts all certificates.
 */
object DnsResolver {
    val doh: Dns = Dns.SYSTEM
}
