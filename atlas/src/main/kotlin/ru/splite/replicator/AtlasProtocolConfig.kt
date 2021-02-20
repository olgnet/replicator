package ru.splite.replicator

data class AtlasProtocolConfig(val n: Int, val f: Int) {

    init {
        assert(n > 2) {
            "n must be more than 2 but received $n"
        }
        val maxF = (n - 1) / 2
        assert(f in 1..maxF) {
            "f must be in range [1, $maxF] but received $f"
        }
    }

    val fastQuorumSize: Int = n / 2 + f

    val slowQuorumSize: Int = f + 1

    val recoveryQuorumSize: Int = n - f
}