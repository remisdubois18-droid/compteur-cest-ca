package fr.remis.cestca;

/** Accès au moteur de reconnaissance Whisper (code natif). */
final class Whisper {
    static {
        System.loadLibrary("cestca");
    }

    private Whisper() { }

    /** Charge un modèle ; renvoie 0 en cas d'échec. */
    static native long charger(String chemin);

    /** Transcrit du son (16 000 échantillons par seconde, valeurs entre -1 et 1) ; renvoie le texte en UTF-8. */
    static native byte[] transcrire(long contexte, float[] son, int threads, int audioCtx);

    static native void liberer(long contexte);
}
