package app.lensly.ui.wallet

import app.lensly.core.wallet.Mnemonic
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class MnemonicQuizTest {

    /** A fixed, valid 24-word phrase, so every run asks about the same words. */
    private val words: List<String> = Mnemonic.fromEntropy(ByteArray(32) { it.toByte() })

    private fun quiz(seed: Int = 1) = MnemonicQuiz(words, random = Random(seed))

    /** Answers every question correctly and returns the questions in the order they were asked. */
    private fun walkQuiz(quiz: MnemonicQuiz): List<MnemonicQuiz.Question> {
        val asked = mutableListOf<MnemonicQuiz.Question>()
        while (true) {
            val question = quiz.current()
            asked += question
            when (quiz.answer(words[question.position - 1])) {
                MnemonicQuiz.Answer.Correct -> Unit
                MnemonicQuiz.Answer.Finished -> return asked
                MnemonicQuiz.Answer.Wrong -> error("a correct answer must not restart the quiz")
            }
        }
    }

    @Test
    fun fixtureIsAValid24WordPhrase() {
        assertEquals(Mnemonic.WORD_COUNT, words.size)
        assertTrue(Mnemonic.isValid(words))
    }

    @Test
    fun asksFourDistinctPositionsWithinTheWords() {
        val positions = walkQuiz(quiz()).map { it.position }
        assertEquals(4, positions.size)
        assertEquals(4, positions.toSet().size)
        assertTrue(positions.all { it in 1..words.size })
    }

    @Test
    fun eachQuestionOffersFourDistinctChoicesIncludingTheRightWord() {
        val wordlist = Bip39Wordlist.words
        for (question in walkQuiz(quiz())) {
            assertEquals(MnemonicQuiz.CHOICE_COUNT, question.choices.size)
            assertEquals(question.choices.size, question.choices.toSet().size)
            assertTrue(words[question.position - 1] in question.choices)
            assertTrue(question.choices.all { it in wordlist })
        }
    }

    @Test
    fun correctAnswersAdvanceAndTheLastOneFinishes() {
        val quiz = quiz()
        assertEquals(0, quiz.currentStep)
        repeat(quiz.questionCount - 1) {
            val question = quiz.current()
            assertEquals(MnemonicQuiz.Answer.Correct, quiz.answer(words[question.position - 1]))
        }
        assertEquals(quiz.questionCount - 1, quiz.currentStep)
        val last = quiz.current()
        assertEquals(MnemonicQuiz.Answer.Finished, quiz.answer(words[last.position - 1]))
    }

    @Test
    fun wrongAnswerRestartsTheQuizFromTheFirstQuestion() {
        val quiz = quiz()
        val first = quiz.current()
        assertEquals(MnemonicQuiz.Answer.Correct, quiz.answer(words[first.position - 1]))
        assertEquals(1, quiz.currentStep)

        val second = quiz.current()
        val wrong = second.choices.first { it != words[second.position - 1] }
        assertEquals(MnemonicQuiz.Answer.Wrong, quiz.answer(wrong))
        assertEquals(0, quiz.currentStep)
    }

    @Test
    fun restartReturnsToTheFirstQuestion() {
        val quiz = quiz()
        val question = quiz.current()
        quiz.answer(words[question.position - 1])
        quiz.restart()
        assertEquals(0, quiz.currentStep)
    }

    @Test
    fun sameSeedAsksTheSameQuestions() {
        assertEquals(walkQuiz(quiz(seed = 7)), walkQuiz(quiz(seed = 7)))
    }

    @Test
    fun stateForCarriesPositionChoicesProgressAndFailure() {
        val quiz = quiz()
        val state = quiz.stateFor(failed = true)
        val question = quiz.current()
        assertEquals(0, state.step)
        assertEquals(quiz.questionCount, state.total)
        assertEquals(question.position, state.position)
        assertEquals(question.choices, state.choices)
        assertTrue(state.failed)
    }

    @Test
    fun rejectsPhrasesThatAreNotTwentyFourWords() {
        assertFailsWith<IllegalArgumentException> { MnemonicQuiz(words.take(23)) }
    }

    @Test
    fun currentThrowsOnceTheQuizIsFinished() {
        val quiz = quiz()
        walkQuiz(quiz)
        assertFailsWith<IllegalStateException> { quiz.current() }
    }
}
