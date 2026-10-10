package rdm.ui;

import static rdm.i18n.Tr.tr;

import rdm.model.Support;

/** The drawing tools of the left bar, with their key (letters, so the same on AZERTY and QWERTY, and in every language). */
enum Tool {
  SELECT('S'),
  NODE('N'),
  BEAM('P'),
  BAR('B'),
  ENCASTREMENT('E', Support.ENCASTREMENT),
  PIVOT('D', Support.PIVOT),
  ROLLER('R', Support.APPUI_SIMPLE),
  SLIDER('G', Support.GLISSIERE),
  SPRING('K', Support.ELASTIQUE),
  FORCE('F'),
  MOMENT('M'),
  DISTRIBUTED('Q'),
  HINGE('H'),
  DELETE('X');

  final char key;
  final Support support;

  Tool(char key) {
    this(key, null);
  }

  Tool(char key, Support support) {
    this.key = key;
    this.support = support;
  }

  String label() {
    return tr("tool." + name());
  }

  String help() {
    return tr("tool." + name() + ".help");
  }
}
