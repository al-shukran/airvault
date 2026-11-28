package app.lumio.receive

import app.lumio.ui.home.AssetIconSpec
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Receive questions: which step asks next, what Back returns to, and the words the share and the note use. */
class ReceiveModelTest {

    private val pulsar = ReceiveAccount(name = "PulsarNova 5", label = "Trading", group = "DeFi", address = ADDRESS)
    private val felix = ReceiveAccount(name = "FelixStone 0", label = "Savings", group = "Core", address = OTHER_ADDRESS)

    private val monad = ReceiveNetwork(name = "Monad Testnet", icon = AssetIconSpec.Letter("M"), accounts = listOf(pulsar))
    private val base = ReceiveNetwork(
        name = "Base L2 on Ethereum",
        icon = AssetIconSpec.Letter("B"),
        accounts = listOf(pulsar, felix),
        accountNote = "Only accounts that can hold USDC on Base are listed.",
    )
    private val arbitrum = ReceiveNetwork(name = "Arbitrum L2 on Ethereum", icon = AssetIconSpec.Letter("A"), accounts = listOf(pulsar, felix))

    private val mon = ReceiveCoin(
        symbol = "MON",
        name = "Monad",
        subtitle = "MON · Ethereum App accounts",
        held = false,
        icon = AssetIconSpec.Letter("M"),
        networks = listOf(monad),
    )
    private val usdc = ReceiveCoin(
        symbol = "USDC",
        name = "USD Coin",
        subtitle = "USDC · on 2 networks",
        held = true,
        icon = AssetIconSpec.Letter("U"),
        networks = listOf(base, arbitrum),
        networkNote = "Bridged USDC, such as USDC.e, is a different asset and isn't listed here.",
    )

    @Test
    fun asksForTheAssetWhenNoCoinIsKnown() {
        assertEquals(ReceiveStep.ChooseAsset(listOf(usdc, mon)), receiveStep(ReceiveAnswers(), listOf(usdc, mon)))
    }

    @Test
    fun takesTheOnlyAssetWithoutAsking() {
        val monOnly = mon.copy(networks = listOf(monad))
        assertEquals(ReceiveStep.Receive(monOnly, monad, pulsar), receiveStep(ReceiveAnswers(), listOf(monOnly)))
    }

    @Test
    fun asksForTheNetworkWhenTheCoinHasSeveral() {
        assertEquals(ReceiveStep.ChooseNetwork(usdc), receiveStep(ReceiveAnswers(coin = usdc), listOf(usdc, mon)))
    }

    @Test
    fun asksForTheAccountWhenSeveralCanReceive() {
        assertEquals(
            ReceiveStep.ChooseAccount(usdc, base),
            receiveStep(ReceiveAnswers(coin = usdc, network = base), listOf(usdc)),
        )
    }

    @Test
    fun takesTheOnlyAccountOfTheNetworkWithoutAsking() {
        val usdcOnMonad = usdc.copy(networks = listOf(monad))
        assertEquals(
            ReceiveStep.Receive(usdcOnMonad, monad, pulsar),
            receiveStep(ReceiveAnswers(coin = usdcOnMonad, network = monad), listOf(usdc)),
        )
    }

    @Test
    fun endsAtTheReceiveSheetWhenEverythingIsKnown() {
        assertEquals(
            ReceiveStep.Receive(usdc, base, felix),
            receiveStep(ReceiveAnswers(coin = usdc, network = base, account = felix), listOf(usdc, mon)),
        )
    }

    @Test
    fun backReturnsToTheAnswersBeforeTheLastPick() {
        val flow = ReceiveFlow(listOf(usdc, mon), ReceiveAnswers())
        assertFalse(flow.canGoBack)

        flow.choose(flow.answers.copy(coin = usdc))
        assertIs<ReceiveStep.ChooseNetwork>(flow.step)
        flow.choose(flow.answers.copy(network = base))
        assertIs<ReceiveStep.ChooseAccount>(flow.step)
        assertTrue(flow.canGoBack)

        flow.back()
        assertIs<ReceiveStep.ChooseNetwork>(flow.step)
        flow.back()
        assertIs<ReceiveStep.ChooseAsset>(flow.step)
        assertFalse(flow.canGoBack)

        flow.back()
        assertIs<ReceiveStep.ChooseAsset>(flow.step)
    }

    @Test
    fun shareSentenceNamesTheCoinTheFullAddressAndTheNetwork() {
        assertEquals(
            "Send MON on $ADDRESS on the Monad Testnet network. Assets sent on other networks may be lost.",
            receiveShareText("MON", ADDRESS, "Monad Testnet"),
        )
    }

    @Test
    fun shareSentenceCarriesTheFullAddressOfEachChain() {
        val addresses = listOf(
            ADDRESS, // EVM
            "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", // Bitcoin
            "So11111111111111111111111111111111111111112", // Solana
            "GD64YIY3TWGDMCNPP553DZPPR6LDUSFQOIJVFDPPXWEG3FVOJCCDBBHU5A", // Algorand
        )
        for (address in addresses) {
            assertEquals(
                "Send ALGO on $address on the Network network. Assets sent on other networks may be lost.",
                receiveShareText("ALGO", address, "Network"),
            )
        }
    }

    @Test
    fun shareSentenceNeverHasAnAccountName() {
        val sentence = receiveShareText("MON", ADDRESS, "Monad Testnet")
        assertFalse(sentence.contains(pulsar.name))
        assertFalse(sentence.contains("Trading"))
        assertFalse(sentence.contains("DeFi"))
    }

    @Test
    fun noteNamesTheNetworkItAcceptsOnly() {
        assertEquals(
            "Send only Monad Testnet assets here. Assets sent on other networks may be lost.",
            receiveNote("Monad Testnet"),
        )
    }

    @Test
    fun monogramIsTheCapitalsOfTheName() {
        assertEquals("PN", monogramOf("PulsarNova 5"))
        assertEquals("FS", monogramOf("FelixStone 0"))
        assertEquals("A", monogramOf("Air 1"))
        assertEquals("N", monogramOf("nothing"))
    }

    private companion object {
        const val ADDRESS = "0x102030405060708090112131415161718191122b"
        const val OTHER_ADDRESS = "0x10203040506070809011213141516171819112aa"
    }
}
