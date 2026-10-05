package id.nusamesh.app.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test vector resmi (RFC 8439, RFC 4231, NIST) + vektor silang dari pustaka `cryptography` Python. */
class CryptoTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun sha256Nist() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hash("abc".encodeToByteArray()).hex())
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).hex(),
        )
    }

    @Test
    fun hmacRfc4231() {
        // Kasus 2: kunci "Jefe".
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Sha256.hmac("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).hex(),
        )
    }

    @Test
    fun chacha20Rfc8439Block() {
        // §2.3.2
        val key = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val block = ChaCha20Poly1305.chachaBlock(key, 1, hex("000000090000004a00000000"))
        assertEquals(
            "10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4ed2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e",
            block.hex(),
        )
    }

    @Test
    fun poly1305Rfc8439() {
        // §2.5.2
        val tag = Poly1305.mac(
            hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b"),
            "Cryptographic Forum Research Group".encodeToByteArray(),
        )
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", tag.hex())
    }

    @Test
    fun aeadRfc8439() {
        // §2.8.2
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".encodeToByteArray()
        val sealed = ChaCha20Poly1305.seal(key, nonce, plaintext, aad)
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", sealed.copyOfRange(sealed.size - 16, sealed.size).hex())
        assertTrue(sealed.copyOf(16).hex() == "d31a8d34648e60db7b86afbc53ef7ec2")
        assertEquals(plaintext.decodeToString(), ChaCha20Poly1305.open(key, nonce, sealed, aad)?.decodeToString())
        // Satu bit diubah → ditolak.
        val tampered = sealed.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertNull(ChaCha20Poly1305.open(key, nonce, tampered, aad))
        assertNull(ChaCha20Poly1305.open(key, nonce, sealed, hex("00")))
    }

    private val aeadVectors = listOf(
        listOf("a31c06bd463e3923bc1aadbde48b16976c080717373b819a068f32b7a6b38b6b", "38729647cfde01c2ce28b26c", "", "", "a7e4039ffac80caabe61cd124504b6f8"),
        listOf("57472737f5c3561a1761185bd8589a43ce0bba75891ff9ec60148d4bd4a09ee2", "dc5c9331b4110ba93ac54afc", "14", "da", "31d7640999ad4cd6981eeaff350e5e42aa"),
        listOf("3bdd19614774a2d55d295e5a35ab44b3efaea5129ba22b88ba3e29766145fdec", "a3b08e38af53d7c4c60e3ad2", "08ce5066441036e9f191e0b75036a7", "7f65e2eaa4752443233fbe8f8943bf", "a82b1fb867c647975811fec36bc219453f51f443a4c3ba1ed2de5f9aeb4654"),
        listOf("956de595665c38ffff23827e17c10cdc1c27a028caae6c9810626198ff778740", "f88ddcf102aeb81daee289c0", "44c4a4571c4b6f287400f4b8e0b843f8", "80c32d81e91bdea04cd7a3819b32275f", "c5b8d30ffaf7d9c279b2f74107b82d4e5282276c9fc40e9ebe33546a9e8e0c0b"),
        listOf("c3298af4c7ec87eb0099527d041ced5ce0fcd4ce4e3d0e3de091f21415bb7cd0", "11fac288c42020a879f28c2a", "4387df9b6cf636ed8ac1bab033b64f66fe", "aba65f70e684731e3f39105605968d3a96", "1bdbb53179d29bd04b79d915dab0bc2061f81045ab677a48ebe2cabdbca64f690c"),
        listOf("380112b5a10f3a11e708dc5412833c47ab7c368a21b9efe19293793ec879ce68", "301818a86e5a6c6977ddba0d", "aca7fba5190f67ba56ccdc1b3f31308972", "236c2e47763fdfec1371cedcdb8c190ca6ff8ad603f817edc0d93c2a687c7b36dd66e70f2a6100fc6343edc8c874496cb2f5bbfec88ea9b77c27304b37f70e", "aa5165b31b19fefe8ed8f40b47aeb61a115c83be22a2e0478c548a7bf67f2a4f02c7d1055fa71f11e12fb5dac644d21361145ef8f5383bd2af34746fc7be997694628a65cf57082d132e03dffe6a84"),
        listOf("94bc8a0fbf500e0c957a80ebda87280ef58214d92f119811acdc3c671ef1e391", "3f94980a9e146ba895908550", "ef4234abb7503d436521aba54c7550edc0ef", "1202759fff90ff19128936814321ee59e111e13e5e482870d58bb44d9cfbfccea78702aad18d4ceea91af0e022431de31bbe8d2745489a35b75734afa2da4381", "baa1c114993070e62a293da62a130f47a70f0fdaa60612149dcde91383c3f6459e012beb53596e2de093a1bd2b350962e92fcdf91366b807a2eeeead6c5e9515b5f9af9cb347df38f827564318564baf"),
        listOf("7d40e7e8d80d17a26cd4460b0055c521a3fa4329bd718db46d8f021c13f1e2b0", "e7268b09d55e958d256e200a", "4e5de6eecbf8dc0ae65b35ae3faa1a5ac78fe2", "df68f99ebf27ecee3cdd29f9cccf2de169062dbcec55c8ee69cdabddbccf3f4428c9b31b61df09db783833d1eb75594ed2cbdf3a3906a831665447dd11f7c54759", "8df54e15c558ad3bb5968a572a30221d50d7fb570d89ce293f4e085583ce16d1df7822e7dc72bd08d976586f3111ebc2472a225b401122620ff4becf2357ab6fec85907d902223757371d498d4346775e7"),
        listOf("a48266adfbd78954f0071de0f8422d94f6fb43091b986f58bac9506f9bfb821d", "62e69330410bb56f0085ecce", "89", "afb8f0bdbcab325d6e11f2aaeb549f50a9d91fb8e64c814faa685367b24b8d20316baaf061adbfe72c9d914d678cd5004d49356ec9949ba752777171ac368279cbe6f5cbbc2ba8154883a9a29e5517d1f3c03cac4f39ce3225060b3efb799cd9c412746ae2a19331b7b2627e663e25a7b001e4c0dcc5e21bc76c382dcdf5b284760c8e3fead91f7422cd76aa87fc8f9851f3c1e4719cd0b8e4816dd4e88c72e528bedc797342c03fd7a346c4c7857ca03d467013b6493c455551e48a1423263b62b127b436106a68548a776a0f34d56b63e7c595f2b205dbe1c393617a01f15a4cc063dae4f4d56b89bfbc8bcc9ae5387c38456f7c076356abadcc67b92ad777eb20fb9f8806e8649790a90615a46d22dd762e0c42615336745356c2e16147c0f3d46b40d5147804bf8a0dff", "71f8d553b20bc77f96c7d5e4ce5d0c5f8e1ced93a56da0c23b554ce95f53d2ade234c522b0fb9fffa2eddc115f2cd2630c3d37b86484188da79ef55274c3d05b185f77fba9a88a47e152f45d73c24d2bfc65bd1b63718007d9b9660503cf4c73db94e3a439477520c43a449c0fe99cd3dd4c1d111a591bd10bf907911f4bed7b061bcf37cbd1e83c3d55e0c2aac3499949153c2456643efba9072f0867a629015bbd42853404a7e5a02c6f56dacd699de847275a0c7864a0f4e87aed4c43c98689f6ed7b4fe78f8616804b9cfae1f8feea64cbe7e4230f2fb7f2a2cf0b116e4a2045a9f62bce524e335c5a183c4f998b1b3f2e211e7a3fe8a7a81bf5986f4d545974a062187247bb490980caf79eadfd15248b217b17649f5b0b7c5701f52680ba2987c2a71c335df7b3760956b192889d573b45dc58a56289daf61a"),
    )
    private val shaVectors = listOf(
        "" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "f35939a611c7f5a60ac107f33f33d6059f273d2079ab1d90f23777b341c45e2a9b9bf6bfb71dc7d129f64f1b9406ed4f93ade8f56065f1" to "59740a04d5a0afe28dd4c967b85a5a03469e9ef2c2b8dcccf74f1c1dbbefd697",
        "b7321397b0d4a03e1ab2c54dd9af99ce1ecbfb90c80a58886da95e1181a55703d96bd27d1b6ef55ca2e4d475b5276f2dbb85f7a6459dceeb" to "bdd92864cdbde6a044f86b64217c8684fe2b995da4cdd2d9ab8a753024aca811",
        "89c67b776fd3bb974452da3ed4ef1647e1733ec076919cab6156077ed9532e7c365acc425747e198b3e1468e0284f230153db8687d8ec23db079a5b67d72ca04" to "cb0da915744e7a0df75581ec29e8ad99fe6f1deecc8758aa8cc18db50f6e3e0a",
        "174b3867b13e4ea9945e798d87586cffbe8c545ab374454e403b1eb831501ebe89f3c3b02f3137bd7b46b996fac2869848fb19d5314b3a5c2d4d03b58820460bf90d8d4ab2f120a3dec07d1adf039248787a70572ff70d40f0dc7a1dd210667d1293a1af0d2626cf90f24d15fe3f1e8ec36a9b98ca9e39" to "e233a4151f46d27d39d0dd6f7ff4d92845d0af4a110f6149f2c2cd51a2670d6f",
        "c6856173e8714cdc96fd6d4e919e0f9cf5bd19f2c335a03643a914283d2c8d1328006873b098784a083b49b448b3dc7412af3bec43c9caa096a9cdef326c1d8b39a526e844d324120f2aca4e98bfd391eb49701f77b04db367f145808a7e7014990ae36ebc529a4006173af6acd6dc9396f305ffc3acd244930ac3c12c7884a671ea472eff956fa2d07df8177859685552ab1adb295469b17e49a9f166d0c28c0974165040521df8c567dd83d3fc00a8de8a76690d30845c9fc17fa071c20d34448c21ed4970e1b27c1f07f9a19bcc3db5284f8d038d681739fed7e91d76f21ea5d5277feeb74a82b4456ad57bfa783e748d256230eb9982bfe122dd1146c5cada6a57efc98144d20048b94cd69694ffa87ddd2672897b58558dc38b6074ee52de30fbb23d92623bdbc6690b51be79b4e9cf6162fda9cad2a6fb267ef6092080f79754de19dfd8701986e97403b82468dea7f8271378c8f843569fb165a614da54daacdb8861f451a0b7e3c27cdf8a099e113ca1afeb49ff3abf176ffa19c2a2b4df19712ab14ce7070b53cb0e4b5b5f6e253e876990aeca2e2b2c149cde619eae3d7fe995243b76a3417541aa02e6cd77e649ad8b281271f158fc964ca3f66cb04074d84d32ff62da7b1b3c61925b934bfeb34b05fad4a865460290ddafc7bef90ce99bbe7fd5e7e749c6cc3a9bcd5a38a2309e40adc1b8c4a8aed623a018e7a0a50a4fc97008945dbb2117e84b53bf6a2c3321c98ae0f85d8780e945d42a41e9d3f17bf7ce4bbfde56cd1d77f61324c1f739dcadb9acfa65f7d8cd8e5d17ca650343891f745eacbfac439561d2a3f05f1bac3b78069ee2f18f53ea9c38a510a2d276e8b34da6681d230bf2094dfe7e1d183ce3892263745eabf3beb2f28a6b96beba27e26aa719d57d9d68f0f34708b05e377171f33cda5c19fbaf5e8be6faa55b0f654630f71ff2d9d27417a936a4a398f8050cc9553efd20c9903411d4c38d359637d0de3b54c625c9e6980046dbfb25fc218a40cc2c1ca9dd0621035bcac93c9652042c430d20bd6b861dbe107972c75c83971b738038f29d0bbac8e8dda8854d75a4f6070fff7ad8666daf1b7db6e87112e614529b251020469fa2958cb65361fe98874b74819a6e19cbb31ddaa7a6e0c48db8dd376e73e33a6956d374666aba18506d50aa415ff427afec791117d415176e18bebd5fcf218e0f96f48f8f54ab1f695adfaaf0c06cdeeab80df749994f5a1a93813627a87b39d81b59d88e5e1dc3479239ce6dd88ff9c4d19f9daca48e069beda8d4b144072e45b3c34feb5659012ede2490a8661124bda2f80717bf8737606b7457285e4fb853c6f1919815e20d2728c19e0cac144571a96c7c9b716a4537c1831d586e1c48adad977c86aa4e0b3865fc99" to "c3c281d4064ec6924fe4b2af4e7a1d3687522decfa9e75c033e44de56082e151",
    )
    private val hmacVectors = listOf(
        Triple("0e01344df236c423c3414a531e017fbf6e2c2161", "88b43a808fd5abce", "526953a82ba80804314277eaa8404dc9cce622dd92c89ecc39ec6d5c3885d3e7"),
        Triple("5a1265dcbd0a6f0475eb13dc50936d9267b5a36a4a1d6705f7532bcdf29e75d4b0eb5c166fd81b3e6f9666861465de4fbe563855c72b1382a21d878231e7c659", "59baf5d1a5d0253c1a2541322c9a27c2c2a7132df3c5a07e76c190c29472aeece190a4a2fc9f52ddf8a0502670117871a14d", "53718ab8c112cbdbd77c9d6c0ecf17780fb30cb4dcc9c941b3c36e32ab346719"),
        Triple("cb46970e5a81124f7673090e5ed44913a5ddfada179d98816276948df4cabde50a73e8cf92a630529a798026f50f731acfe6d657fbb61581a52c0a3fb570fd7086859c285d5fea486368c656ad990dcaa1a5551054188ead624840b9daa8f6e89adf26551495a924ea594ff7a7b2a9642198b5f0154f8f60a4ca54d020abb3d4f2bdff", "afe98617a5ab6c825c045c4f2ef33657f2c47c3139ff2327134bd8c91981c58ad5bde28609a956e0c49e21986027292ed4b1c59fcfe72ab8700b695dadb83cf8719c48c0bfc8723b883d4ff7cfc878e7d5315eadf292fc7076c448c76180876bf729d133", "18341e45289ac40a5a897b906044c6a6632f722c32a528c47281081582de31f5"),
    )

    @Test
    fun matchesPythonCryptography() {
        for ((k, n, a, p, c) in aeadVectors) {
            assertEquals(c, ChaCha20Poly1305.seal(hex(k), hex(n), hex(p), hex(a)).hex(), "seal len ${p.length / 2}")
            assertEquals(p, ChaCha20Poly1305.open(hex(k), hex(n), hex(c), hex(a))?.hex())
        }
        for ((m, d) in shaVectors) assertEquals(d, Sha256.hash(hex(m)).hex(), "sha len ${m.length / 2}")
        for ((k, m, d) in hmacVectors) assertEquals(d, Sha256.hmac(hex(k), hex(m)).hex(), "hmac key ${k.length / 2}")
    }

    @Test
    fun secureRandomLooksRandom() {
        val a = secureRandomBytes(32)
        val b = secureRandomBytes(32)
        assertEquals(32, a.size)
        assertTrue(!a.contentEquals(b))
    }
}
