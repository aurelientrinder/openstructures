package rdm.ui;

import rdm.model.Support;

/** The drawing tools of the left bar, with their key (letters, so the same on AZERTY and QWERTY). */
enum Tool {
  SELECT("Sélection", 'S', "Choisir un nœud ou un élément ; glisser un nœud pour le déplacer"),
  NODE("Nœud", 'N', "Cliquer pour poser un nœud (sur un élément : le coupe en deux)"),
  BEAM("Poutre", 'P', "Cliquer deux points : une poutre entre eux (enchaîne ; Échap pour arrêter)"),
  BAR("Barre", 'B', "Barre de treillis, articulée aux deux bouts (enchaîne ; Échap pour arrêter)"),
  ENCASTREMENT("Encastrement", 'E', "Cliquer un nœud : encastrement (bloque x, y et la rotation)", Support.ENCASTREMENT),
  PIVOT("Pivot", 'D', "Cliquer un nœud : pivot / appui double (bloque x et y)", Support.PIVOT),
  ROLLER("Appui simple", 'R', "Cliquer un nœud : appui simple (bloque la normale ; angle dans les propriétés)", Support.APPUI_SIMPLE),
  SLIDER("Glissière", 'G', "Cliquer un nœud : glissière (bloque la normale et la rotation)", Support.GLISSIERE),
  FORCE("Force", 'F', "Cliquer un nœud : force (kN)"),
  MOMENT("Couple", 'M', "Cliquer un nœud : couple (kN·m, sens trigonométrique positif)"),
  DISTRIBUTED("Charge répartie", 'Q', "Cliquer un élément : charge uniforme (kN/m)"),
  HINGE("Rotule", 'H', "Cliquer un élément près d'un bout : rotule (articulation) à ce bout"),
  DELETE("Effacer", 'X', "Cliquer un nœud ou un élément pour l'effacer");

  final String label;
  final char key;
  final String help;
  final Support support;

  Tool(String label, char key, String help) {
    this(label, key, help, null);
  }

  Tool(String label, char key, String help, Support support) {
    this.label = label;
    this.key = key;
    this.help = help;
    this.support = support;
  }
}
