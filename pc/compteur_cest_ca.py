# -*- coding: utf-8 -*-
"""
Compteur de « c'est ça » — version ordinateur (Windows).

Le micro enregistre en continu ; le son est découpé en tranches de quelques secondes
(coupées dans un blanc) et transcrit par Whisper (faster-whisper), entièrement sur l'ordinateur.
Chaque « c'est ça » trouvé dans le texte fait monter le compteur.
"""
import os
import queue
import re
import sys
import threading
import time
import tkinter as tk
from tkinter import ttk

import numpy as np

FREQ = 16000                 # échantillons par seconde attendus par Whisper
BLOC = FREQ // 10            # 0,1 s
TRANCHE_MAX = FREQ * 8       # on analyse au plus 8 s à la fois…
TRANCHE_MIN = FREQ * 5       # …en coupant dans un blanc entre 5 et 8 s

MOTIF = re.compile(r"\b(?:[cs]\s*['’]?\s*est|sait|ses|ces|cé)\s*(?:ça|ca|sa)\b", re.IGNORECASE)

FOND, CARTE, ACCENT, VERT, ROUGE, DOUX = "#1b1f3b", "#262b52", "#ffb703", "#2ec27e", "#e5484d", "#a9adc9"

MOTEURS = {
    "Rapide (inclus)": "small",
    "Précis (téléchargé au 1er usage, ~1,5 Go)": "medium",
}


def dossier_programme():
    """Dossier où se trouvent les fichiers livrés avec le programme."""
    if getattr(sys, "frozen", False):
        return getattr(sys, "_MEIPASS", os.path.dirname(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


def dossier_donnees():
    d = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "CompteurCestCa")
    os.makedirs(d, exist_ok=True)
    return d


def compter(texte):
    return len(MOTIF.findall(texte.replace("’", "'")))


def charger_modele(nom):
    from faster_whisper import WhisperModel
    import ctranslate2

    inclus = os.path.join(dossier_programme(), "modeles", nom)
    source = inclus if os.path.isdir(inclus) else nom
    try:
        gpu = ctranslate2.get_cuda_device_count() > 0
    except Exception:
        gpu = False
    if gpu:
        try:
            return WhisperModel(source, device="cuda", compute_type="float16",
                                download_root=dossier_donnees()), "carte graphique"
        except Exception:
            pass
    coeurs = os.cpu_count() or 4
    return WhisperModel(source, device="cpu", compute_type="int8", cpu_threads=max(2, coeurs - 1),
                        download_root=dossier_donnees()), "processeur"


def transcrire(modele, son):
    segments, _ = modele.transcribe(
        son, language="fr", beam_size=1, best_of=1, temperature=0.0,
        condition_on_previous_text=False, vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 300},
        without_timestamps=True,
    )
    return " ".join(s.text.strip() for s in segments).strip()


def chercher_blanc(son):
    """Moment le plus calme entre 5 et 8 s, pour ne pas couper un mot en deux."""
    fin = min(len(son), TRANCHE_MAX)
    meilleur, plus_calme = fin, None
    for debut in range(TRANCHE_MIN, fin - BLOC + 1, BLOC // 2):
        e = float(np.mean(son[debut:debut + BLOC] ** 2))
        if plus_calme is None or e < plus_calme:
            plus_calme, meilleur = e, debut + BLOC // 2
    return meilleur


class Application:
    def __init__(self, racine):
        self.racine = racine
        self.compte = 0
        self.en_ecoute = False
        self.debut = 0.0
        self.cumul = 0.0
        self.modeles = {}
        self.file_son = queue.Queue()
        self.file_ui = queue.Queue()
        self.flux = None
        self.session = 0
        self.historique = []
        self.analyse_en_cours = False

        racine.title("Compteur de « c'est ça »")
        racine.configure(bg=FOND)
        racine.geometry("560x760")
        racine.minsize(460, 600)

        tk.Label(racine, text="Compteur de « c'est ça »", bg=FOND, fg=DOUX,
                 font=("Segoe UI", 15)).pack(pady=(16, 6))

        options = tk.Frame(racine, bg=FOND)
        options.pack(fill="x", padx=20)
        tk.Label(options, text="Moteur :", bg=FOND, fg=DOUX, font=("Segoe UI", 10)).grid(row=0, column=0, sticky="w")
        self.choix_moteur = ttk.Combobox(options, values=list(MOTEURS), state="readonly", width=42)
        self.choix_moteur.current(0)
        self.choix_moteur.grid(row=0, column=1, sticky="we", padx=6, pady=3)
        tk.Label(options, text="Micro :", bg=FOND, fg=DOUX, font=("Segoe UI", 10)).grid(row=1, column=0, sticky="w")
        self.micros = self.lister_micros()
        self.choix_micro = ttk.Combobox(options, values=[m[1] for m in self.micros], state="readonly", width=42)
        if self.micros:
            self.choix_micro.current(0)
        self.choix_micro.grid(row=1, column=1, sticky="we", padx=6, pady=3)
        options.columnconfigure(1, weight=1)

        self.cercle = tk.Canvas(racine, width=250, height=250, bg=FOND, highlightthickness=0)
        self.cercle.pack(pady=12)
        self.anneau = self.cercle.create_oval(10, 10, 240, 240, outline="#343a6b", width=7, fill=CARTE)
        self.nombre = self.cercle.create_text(125, 112, text="0", fill=ACCENT, font=("Segoe UI", 72, "bold"))
        self.cercle.create_text(125, 182, text="« c'est ça » entendus", fill=DOUX, font=("Segoe UI", 11))

        self.etat = tk.Label(racine, text="● À l'arrêt", bg=FOND, fg=DOUX, font=("Segoe UI", 11))
        self.etat.pack()

        boutons = tk.Frame(racine, bg=FOND)
        boutons.pack(fill="x", padx=20, pady=12)
        self.bouton = tk.Button(boutons, text="▶  Démarrer", command=self.basculer, bg=VERT, fg="white",
                                activebackground=VERT, font=("Segoe UI", 14, "bold"), relief="flat", height=2)
        self.bouton.pack(side="left", fill="x", expand=True, padx=(0, 10))
        tk.Button(boutons, text="Remettre à 0", command=self.remettre_a_zero, bg="#3b4175", fg="white",
                  activebackground="#3b4175", font=("Segoe UI", 11), relief="flat", height=2,
                  width=14).pack(side="left")

        stats = tk.Frame(racine, bg=FOND)
        stats.pack(fill="x", padx=20)
        self.duree = self.stat(stats, "00:00", "durée d'écoute")
        self.rythme = self.stat(stats, "–", "par minute")

        self.texte = tk.Text(racine, bg=CARTE, fg=DOUX, font=("Segoe UI", 11), wrap="word", relief="flat",
                             padx=12, pady=10, height=10)
        self.texte.pack(fill="both", expand=True, padx=20, pady=(12, 18))
        self.texte.tag_configure("trouve", background=ACCENT, foreground=FOND, font=("Segoe UI", 11, "bold"))
        self.afficher_texte()

        racine.protocol("WM_DELETE_WINDOW", self.fermer)
        self.racine.after(200, self.boucle_ui)

    # ------------------------------------------------------------ écran
    def stat(self, parent, valeur, legende):
        carte = tk.Frame(parent, bg=CARTE)
        carte.pack(side="left", fill="x", expand=True, padx=4)
        v = tk.Label(carte, text=valeur, bg=CARTE, fg="white", font=("Segoe UI", 18, "bold"))
        v.pack(pady=(8, 0))
        tk.Label(carte, text=legende, bg=CARTE, fg=DOUX, font=("Segoe UI", 9)).pack(pady=(0, 8))
        return v

    def lister_micros(self):
        try:
            import sounddevice as sd
            liste = []
            defaut = sd.default.device[0]
            for i, d in enumerate(sd.query_devices()):
                if d["max_input_channels"] > 0 and sd.query_hostapis(d["hostapi"])["name"] == "MME":
                    nom = d["name"] + ("  (par défaut)" if i == defaut else "")
                    liste.append((i, nom))
            liste.sort(key=lambda m: 0 if m[0] == defaut else 1)
            return liste or [(None, "Micro par défaut")]
        except Exception:
            return [(None, "Micro par défaut")]

    def mettre_etat(self, texte, couleur):
        self.etat.configure(text=texte, fg=couleur)

    def afficher_texte(self):
        self.texte.configure(state="normal")
        self.texte.delete("1.0", "end")
        if not self.historique:
            self.texte.insert("end", "Le texte entendu s'affichera ici, toutes les quelques secondes.")
        for phrase in self.historique[-15:]:
            debut = 0
            for m in MOTIF.finditer(phrase.replace("’", "'")):
                self.texte.insert("end", phrase[debut:m.start()])
                self.texte.insert("end", phrase[m.start():m.end()], "trouve")
                debut = m.end()
            self.texte.insert("end", phrase[debut:] + "\n")
        self.texte.see("end")
        self.texte.configure(state="disabled")

    def ajouter(self, n):
        self.compte += n
        self.cercle.itemconfigure(self.nombre, text=str(self.compte))
        self.cercle.itemconfigure(self.anneau, outline=ACCENT)
        self.racine.after(400, lambda: self.cercle.itemconfigure(
            self.anneau, outline=VERT if self.en_ecoute else "#343a6b"))

    def duree_totale(self):
        return self.cumul + (time.time() - self.debut if self.en_ecoute else 0)

    # ------------------------------------------------------------ démarrage / arrêt
    def basculer(self):
        if self.en_ecoute:
            self.arreter()
        else:
            self.demarrer()

    def demarrer(self):
        nom = MOTEURS[self.choix_moteur.get()]
        self.bouton.configure(state="disabled")
        if nom not in self.modeles:
            msg = "Préparation du moteur…" if nom == "small" else \
                "Téléchargement et préparation du moteur précis (la première fois, quelques minutes)…"
            self.mettre_etat(msg, ACCENT)
        threading.Thread(target=self.preparer_et_ecouter, args=(nom,), daemon=True).start()

    def preparer_et_ecouter(self, nom):
        try:
            if nom not in self.modeles:
                self.modeles[nom] = charger_modele(nom)
            self.file_ui.put(("pret", nom))
        except Exception as e:
            self.file_ui.put(("erreur", f"Impossible de préparer le moteur : {e}"))

    def ouvrir_micro(self, nom):
        import sounddevice as sd
        idx = self.choix_micro.current()
        appareil = self.micros[idx][0] if 0 <= idx < len(self.micros) else None
        info = sd.query_devices(appareil, "input")
        freq_micro = int(info["default_samplerate"]) or 44100
        file_son = queue.Queue()
        self.file_son = file_son

        def rappel(donnees, nb, temps, statut):
            file_son.put(donnees[:, 0].copy())

        self.flux = sd.InputStream(device=appareil, channels=1, samplerate=freq_micro, dtype="float32",
                                   blocksize=int(freq_micro / 10), callback=rappel)
        self.flux.start()
        self.session += 1
        session = self.session
        modele, materiel = self.modeles[nom]
        threading.Thread(target=self.analyser, args=(session, modele, file_son, freq_micro),
                         daemon=True).start()
        return materiel

    def arreter(self):
        if self.en_ecoute:
            self.cumul += time.time() - self.debut
        self.en_ecoute = False
        if self.flux is not None:
            try:
                self.flux.stop()
                self.flux.close()
            except Exception:
                pass
            self.flux = None
        self.file_son.put(None)  # fin : l'analyse termine le son déjà enregistré
        self.bouton.configure(text="▶  Démarrer", bg=VERT, activebackground=VERT, state="normal")
        self.cercle.itemconfigure(self.anneau, outline="#343a6b")
        self.choix_moteur.configure(state="readonly")
        self.choix_micro.configure(state="readonly")
        self.mettre_etat("● Arrêté – fin de l'analyse…" if self.analyse_en_cours else "● À l'arrêt",
                         ACCENT if self.analyse_en_cours else DOUX)

    def remettre_a_zero(self):
        self.compte = 0
        self.cumul = 0.0
        if self.en_ecoute:
            self.debut = time.time()
        self.historique.clear()
        self.cercle.itemconfigure(self.nombre, text="0")
        self.afficher_texte()

    def fermer(self):
        try:
            if self.flux is not None:
                self.flux.stop()
                self.flux.close()
        except Exception:
            pass
        self.racine.destroy()
        os._exit(0)

    # ------------------------------------------------------------ analyse (tâche de fond)
    def analyser(self, session, modele, file_son, freq_micro):
        tampon = np.zeros(0, dtype=np.float32)
        while True:
            bloc = file_son.get()
            fin = bloc is None
            if not fin:
                if freq_micro != FREQ:  # mise à 16 000 échantillons par seconde
                    n = int(round(len(bloc) * FREQ / freq_micro))
                    bloc = np.interp(np.linspace(0, len(bloc) - 1, n), np.arange(len(bloc)), bloc).astype(np.float32)
                tampon = np.concatenate([tampon, bloc])
            while len(tampon) >= TRANCHE_MAX:
                coupe = chercher_blanc(tampon)
                self.transcrire_tranche(session, modele, tampon[:coupe], file_son)
                tampon = tampon[coupe:]
            if fin:
                if len(tampon) > FREQ // 2:
                    self.transcrire_tranche(session, modele, tampon, file_son)
                self.file_ui.put(("fin_analyse", session))
                return

    def transcrire_tranche(self, session, modele, son, file_son):
        if float(np.sqrt(np.mean(son ** 2))) < 0.0015:  # silence complet
            return
        self.analyse_en_cours = True
        try:
            texte = transcrire(modele, son)
        except Exception as e:
            texte = ""
            self.file_ui.put(("erreur", f"Erreur d'analyse : {e}"))
        finally:
            self.analyse_en_cours = False
        if texte:
            self.file_ui.put(("texte", session, texte))

    # ------------------------------------------------------------ mise à jour de la fenêtre
    def boucle_ui(self):
        try:
            while True:
                msg = self.file_ui.get_nowait()
                if msg[0] == "pret":
                    try:
                        materiel = self.ouvrir_micro(msg[1])
                    except Exception as e:
                        self.mettre_etat(f"Impossible d'ouvrir le micro : {e}", ROUGE)
                        self.bouton.configure(state="normal")
                        continue
                    self.materiel = materiel
                    self.en_ecoute = True
                    self.debut = time.time()
                    self.bouton.configure(text="■  Arrêter", bg=ROUGE, activebackground=ROUGE, state="normal")
                    self.cercle.itemconfigure(self.anneau, outline=VERT)
                    self.choix_moteur.configure(state="disabled")
                    self.choix_micro.configure(state="disabled")
                elif msg[0] == "erreur":
                    self.mettre_etat(msg[1], ROUGE)
                    self.bouton.configure(state="normal")
                elif msg[0] == "texte" and msg[1] == self.session:
                    texte = msg[2]
                    bas = texte.lower()
                    if "sous-titr" in bas or "amara.org" in bas:
                        continue
                    n = compter(texte)
                    if n:
                        self.ajouter(n)
                    self.historique.append(texte)
                    del self.historique[:-50]
                    self.afficher_texte()
                elif msg[0] == "fin_analyse" and msg[1] == self.session and not self.en_ecoute:
                    self.mettre_etat("● À l'arrêt", DOUX)
        except queue.Empty:
            pass

        if self.en_ecoute:
            retard = self.file_son.qsize() / 10.0
            if retard >= 4:
                self.mettre_etat(f"● J'écoute… (analyse en retard de {retard:.0f} s, rien n'est perdu)", ACCENT)
            else:
                self.mettre_etat(f"● J'écoute… (analyse sur le {self.materiel}, texte toutes les ~5 s)", VERT)
        s = int(self.duree_totale())
        self.duree.configure(text=f"{s // 60:02d}:{s % 60:02d}")
        minutes = self.duree_totale() / 60
        self.rythme.configure(text=f"{self.compte / minutes:.1f}".replace(".", ",") if minutes >= 0.25 else "–")
        self.racine.after(200, self.boucle_ui)


def test_automatique():
    """Vérifie que le moteur se charge et tourne (utilisé lors de la fabrication du programme)."""
    modele, materiel = charger_modele("small")
    son = (np.random.randn(FREQ * 3) * 0.01).astype(np.float32)
    texte = transcrire(modele, son)
    n = compter("Oui c'est ça, C’est ça ! c'est sa")
    with open(os.environ.get("FICHIER_TEST", "test_resultat.txt"), "w", encoding="utf-8") as f:
        f.write(f"TEST OK | {materiel} | {texte!r} | comptage={n}\n")


if __name__ == "__main__":
    if "--test" in sys.argv:
        test_automatique()
        sys.exit(0)
    fenetre = tk.Tk()
    Application(fenetre)
    fenetre.mainloop()
