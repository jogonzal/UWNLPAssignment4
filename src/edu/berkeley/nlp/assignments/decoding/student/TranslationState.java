package edu.berkeley.nlp.assignments.decoding.student;

import edu.berkeley.nlp.langmodel.EnglishWordIndexer;
import edu.berkeley.nlp.langmodel.NgramLanguageModel;
import edu.berkeley.nlp.mt.decoder.Decoder;
import edu.berkeley.nlp.mt.decoder.DistortionModel;
import edu.berkeley.nlp.mt.phrasetable.ScoredPhrasePairForSentence;
import edu.berkeley.nlp.util.StrUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by jogonzal on 12/3/2015.
 */
public class TranslationState {

    @Override
    public String toString() {
        List<Integer> list = new ArrayList<>(TranslatedFlags.length);
        for (boolean element : TranslatedFlags){
            list.add(element ? 1 : 0);
        }
        return "[State " + StrUtils.join(list) + ", score=" + CurrentScore + "Position=" + Phrase.getEnd() + "Phrase=" + Phrase + "]";
    }

    public boolean[] TranslatedFlags;
    public double CurrentScore;
    public TranslationState PreviousState;
    public boolean IsFinal;
    ScoredPhrasePairForSentence Phrase;

    public List<Integer> pastTwoWords;

    public TranslationState(boolean[] translatedFlags, TranslationState previousState, ScoredPhrasePairForSentence phrase){
        TranslatedFlags = translatedFlags;
        PreviousState = previousState;
        IsFinal = true;
        Phrase = phrase;
        for (int i = 0; i < TranslatedFlags.length; i++){
            if (!TranslatedFlags[i]){
                IsFinal = false;
                break;
            }
        }

        pastTwoWords = new ArrayList<Integer>(2);
        TranslationState stateToLookInto = previousState;
        while(pastTwoWords.size() < 2 && stateToLookInto != null){
            int[] english = stateToLookInto.Phrase.english.indexedEnglish;
            int addingOffset = english.length - 1;
            while(pastTwoWords.size() < 2 && addingOffset >= 0){
                pastTwoWords.add(0, english[addingOffset]);
            }
            stateToLookInto = previousState.PreviousState;
        }
        while (pastTwoWords.size() < 2){
            pastTwoWords.add(0, EnglishWordIndexer.getIndexer().addAndGetIndex(NgramLanguageModel.START));
        }
    }

    public static TranslationState BuildTranslationState(TranslationState previousState, ScoredPhrasePairForSentence phrasePair, NgramLanguageModel lm, DistortionModel dm){
        boolean[] translatedFlags = new boolean[previousState.TranslatedFlags.length];

        for(int i = 0; i < translatedFlags.length; i++){
            translatedFlags[i] = previousState.TranslatedFlags[i];
        }
        for (int i = phrasePair.getStart(); i < phrasePair.getEnd(); i++){
            if (translatedFlags[i]){
                throw new StackOverflowError();
            }
            translatedFlags[i] = true;
        }

        TranslationState state = new TranslationState(translatedFlags, previousState, phrasePair);
        state.CurrentScore = ScoreState(state, lm, dm);

        return state;
    }

    private static double ScoreState(TranslationState state, NgramLanguageModel lm, DistortionModel dm){
        return ScoreStateInefficient(state, lm, dm);
    }

    private static double ScoreStateInefficient(TranslationState state, NgramLanguageModel lm, DistortionModel dm) {
        List<ScoredPhrasePairForSentence> phrases = TranslationState.BuildPhraseListFromState(state);

        if (lm != null && dm != null){
            return CustomScoreFunction(phrases, lm, dm);
        }

        if (lm != null) {
            // Need to add language model to score
            double phraseScore = 0;
            for (ScoredPhrasePairForSentence phrase : phrases) {
                phraseScore += phrase.score;
            }
            double lmScore = Decoder.StaticMethods.scoreSentenceWithLm(Decoder.StaticMethods.extractEnglish(phrases), lm, EnglishWordIndexer.getIndexer());
            return phraseScore + lmScore;
        } else {
            double phraseScore = 0;
            for (ScoredPhrasePairForSentence phrase : phrases) {
                phraseScore += phrase.score;
            }
            return phraseScore;
        }
    }

    private static double ScoreStateEfficient(TranslationState state, NgramLanguageModel lm, DistortionModel dm) {
        double accumulatedScore = 0;
        if (state.PreviousState != null){
            accumulatedScore = state.PreviousState.CurrentScore;
        }

        // Phrase score
        double phraseScore = state.Phrase.score;
        accumulatedScore += phraseScore;

        // LM score
        if (lm != null){
            int[] arr = new int[state.Phrase.english.indexedEnglish.length + 2];
            arr[0] = state.pastTwoWords.get(0);
            arr[1] = state.pastTwoWords.get(1);
            for(int i = 0; i < state.Phrase.english.indexedEnglish.length; i++){
                arr[2 + i] = state.Phrase.english.indexedEnglish[i];
            }
            double lmScore = lm.getNgramLogProbability(arr, 2, 2 + state.Phrase.english.indexedEnglish.length);
            accumulatedScore += lmScore;
        }

        // DM score
        if (dm != null){
            // Need to add language model to score
            int previousEnd = 0;
            if (state.PreviousState != null){
                previousEnd = state.PreviousState.Phrase.getEnd();
            }
            double distortionModel = dm.getDistortionScore(previousEnd, state.Phrase.getStart());
            accumulatedScore += distortionModel;
        }

        return accumulatedScore;
    }

    public static TranslationState BuildInitialTranslationState(ScoredPhrasePairForSentence firstPair, Integer sentenceLength, NgramLanguageModel lm, DistortionModel dm){

        boolean[] flags = new boolean[sentenceLength];
        for(int i = 0; i < flags.length; i++){
            flags[i] = false;
        }

        for (int i = firstPair.getStart(); i < firstPair.getEnd(); i++){
            flags[i] = true;
        }

        TranslationState state = new TranslationState(flags, null, firstPair);
        state.CurrentScore = ScoreState(state, lm, dm);

        return state;
    }

    public static List<ScoredPhrasePairForSentence> BuildPhraseListFromState(TranslationState first) {
        // Simply traverse
        ArrayList<ScoredPhrasePairForSentence> phrases = new ArrayList<>();
        while(first != null){
            phrases.add(0, first.Phrase);
            first = first.PreviousState;
        }
        return phrases;
    }

    public static double CustomScoreFunction(List<ScoredPhrasePairForSentence> hyp, NgramLanguageModel languageModel, DistortionModel dm){
        double score = 0.0;
        double dmScore = 0.0;
        ScoredPhrasePairForSentence last = null;
        for (ScoredPhrasePairForSentence s : hyp) {
            score += s.score;
            final int lastEnd = last == null ? 0 : last.getEnd();
            final double distortionScore = dm.getDistortionScore(lastEnd, s.getStart());
            dmScore += distortionScore;
            last = s;
        }
        score += Decoder.StaticMethods.scoreSentenceWithLm(Decoder.StaticMethods.extractEnglish(hyp), languageModel, EnglishWordIndexer.getIndexer());
        score += dmScore * 7;
        return score;
    }

    public boolean ShouldBeAvoided(int distortionLimit) {
        // Check if this state leaves blanks that are at more than "distortionLimit" far from any blank or the current position
        List<DecoderBase.StartAndEnd> startAndEnds = DecoderBase.GetAvailablePositionsAndLengths(TranslatedFlags);
        // The distance between those should NOT be more than the distortion limit
        int currentPosition = Phrase.getEnd();
        if (startAndEnds.size() > 1){
            DecoderBase.StartAndEnd previous = null;
            for(DecoderBase.StartAndEnd startAndEnd : startAndEnds){
                if (previous == null){
                    previous = startAndEnd;
                    continue;
                }
                if (startAndEnd.Start - previous.End > distortionLimit){
                    return true;
                }
            }
            return false;
        } else if (startAndEnds.size() == 1){
            return (Math.abs(startAndEnds.get(0).Start - currentPosition) > distortionLimit) && (Math.abs(startAndEnds.get(0).End - currentPosition) > distortionLimit);
        }

        throw new StackOverflowError();
    }
}
