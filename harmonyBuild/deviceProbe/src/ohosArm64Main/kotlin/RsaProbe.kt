package app.bucc.probe

import app.bucc.api.plantform.PlatformRsaPkcs1Encrypt
import kotlin.io.encoding.Base64

/** Public key belongs only to a generated synthetic fixture; never used by product code. */
internal fun rsaProbe() {
  val key =
      Base64.decode(
          "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAs6nV3QG7I+F+uRMY/6CL2oaLGCtiw7DUC2rgRWuwMMPJ5WzRY23qyZxcpFdl4UqmZqZIlnRXYHoKb6o/O8gKrtcbMGERt7VNRfIGbyu81jp72/oKK9IEpRCG32R5urqzafLRMSy2eXjm0uskckxirbe5CeDV0sftKTfvOcXCj95yV0PBFgMaJGW58Wl4zds7girHmtmJgtUG0HNA3GLaj35TBTnVSSfKdKAhf6aSIHmjHVj8UPAj5ZKRIAlE4rzHjdpk1BOGIp0ldWTYatvkmVVTsw0PcUKz30Yd6lwBA8zkLrOsyxrK0MkEP0A/HwbPjs8vz1p2Z5nJI07o03igDQIDAQAB"
      )
  val input = "BUCC synthetic native RSA fixture".encodeToByteArray()
  val first = PlatformRsaPkcs1Encrypt.encrypt(input, key)
  val second = PlatformRsaPkcs1Encrypt.encrypt(input, key)
  check(first.size == 256 && second.size == 256 && !first.contentEquals(second))
  println("BUCC_RSA_FIXTURE " + Base64.encode(first))
}
