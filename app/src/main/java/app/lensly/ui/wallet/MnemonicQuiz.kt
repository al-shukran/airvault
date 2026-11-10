package app.lensly.ui.wallet

import app.lensly.core.wallet.Mnemonic
import kotlin.random.Random

/**
 * What [MnemonicConfirmScreen] shows for one question. [step] is zero-based, [position] is the
 * one-based word number being asked, and [failed] reports that the last pick was wrong.
 */
data class ConfirmState(
    val step: Int,
    val total: Int,
    val position: Int,
    val choices: List<String>,
    val failed: Boolean = false,
)

/**
 * Checks that the owner wrote the words down. Each question asks for one word by its position and
 * offers the right word with three random BIP39 words. A wrong answer restarts the quiz with new
 * positions and new choices.
 *
 * Pure logic with no Android types. Pass a seeded [random] in tests to make the questions repeatable.
 */
class MnemonicQuiz(
    private val words: List<String>,
    private val wordlist: List<String> = Bip39Wordlist.words,
    private val random: Random = Random.Default,
    val questionCount: Int = DEFAULT_QUESTION_COUNT,
) {
    /** One question. [position] is 1-based. [choices] holds the right word once, plus decoys, shuffled. */
    data class Question(val position: Int, val choices: List<String>)

    /**
     * Result of [answer]. Correct means the quiz continues. Finished means the last question was
     * answered correctly. Wrong means the quiz restarted.
     */
    enum class Answer { Correct, Wrong, Finished }

    private var questions: List<Question>
    private var step = 0

    init {
        require(words.size == Mnemonic.WORD_COUNT) { "quiz needs ${Mnemonic.WORD_COUNT} words" }
        require(questionCount in 1..words.size) { "questionCount must be between 1 and ${words.size}" }
        require(wordlist.size > CHOICE_COUNT) { "wordlist is too small for the quiz" }
        questions = buildQuestions()
    }

    /** Zero-based index of the question being asked. */
    val currentStep: Int get() = step

    /** The question on screen. Throws once the quiz has finished. */
    fun current(): Question {
        check(step < questions.size) { "quiz is already finished" }
        return questions[step]
    }

    /** Checks [word] against the current question. A wrong answer restarts the quiz. */
    fun answer(word: String): Answer {
        val question = current()
        if (word != words[question.position - 1]) {
            restart()
            return Answer.Wrong
        }
        step++
        return if (step == questions.size) Answer.Finished else Answer.Correct
    }

    /** Starts over with new positions and new choices. */
    fun restart() {
        questions = buildQuestions()
        step = 0
    }

    /** Snapshot of the current question for the UI. */
    fun stateFor(failed: Boolean): ConfirmState {
        val question = current()
        return ConfirmState(
            step = step,
            total = questionCount,
            position = question.position,
            choices = question.choices,
            failed = failed,
        )
    }

    private fun buildQuestions(): List<Question> {
        val positions = (1..words.size).shuffled(random).take(questionCount)
        return positions.map { position ->
            val correct = words[position - 1]
            val decoys = LinkedHashSet<String>()
            while (decoys.size < CHOICE_COUNT - 1) {
                val candidate = wordlist[random.nextInt(wordlist.size)]
                if (candidate != correct) decoys.add(candidate)
            }
            Question(position = position, choices = (decoys + correct).shuffled(random))
        }
    }

    companion object {
        const val DEFAULT_QUESTION_COUNT = 4
        const val CHOICE_COUNT = 4
    }
}
