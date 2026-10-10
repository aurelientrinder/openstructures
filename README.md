# RDM — Ossatures planes (proof of concept)

A small Résistance des Matériaux application in Java, in the spirit of RDM7's *Ossatures* module: draw plane
beams and trusses, put supports and loads on them, and see straight away how they deform, with the
internal forces (N, T, Mf), the reactions and the degree of hyperstatism. Every change is solved again live,
including while a node is being dragged.

Plain Java 21 + Swing, no dependencies, no build tool.

## Run

```bat
run.cmd          :: builds rdm.jar and starts it
build.cmd        :: only builds rdm.jar (then: java -jar rdm.jar, or double-click it)
verify.cmd       :: the solver against closed-form results (53 checks)
```

## Use

| Tool (key) | What it does |
| --- | --- |
| Sélection (S) | select a node or an element, drag a node |
| Nœud (N) | place a node; on an element, cuts it in two |
| Poutre (P) / Barre (B) | click two points; chains, Échap or right-click stops. A *barre* is hinged at both ends |
| Encastrement (E), Pivot (D), Appui simple (R), Glissière (G) | click a node (again: removes it). The normal of an appui simple / glissière is set in the properties |
| Force (F), Couple (M) | click a node, type the values (kN, kN·m, counter-clockwise positive) |
| Charge répartie (Q) | click an element: uniform load in kN per metre of element, global axes |
| Rotule (H) | click an element near one end: hinge (articulation) there |
| Effacer (X) | click a node or an element; Suppr deletes the selection |

Right or middle drag pans, the wheel zooms. Hover the structure to read N, T, Mf and the displacement at that
point in the status bar. Ctrl+Z / Ctrl+Y undo and redo. Fichier › Exemples has six ready-made structures.
The « Résultats » tab lists every number (displacements, reactions, end forces, maxima, σ max).

Units: lengths in m, forces in kN, sections in mm (IPE from the table, or A/I/v in cm), E in MPa.
Files: `.rdm`, a plain text format described in `src/rdm/io/StructureFile.java`.

## How it works

- `rdm.model` — `Structure` (nodes, elements), `Node` (support, nodal load), `Element` (hinges, section,
  material, distributed load), `Support`, `Section`, `Material`, `Examples`.
- `rdm.solver` — displacement method (`Solver`): 3 unknowns per node (ux, uy, θz), Euler–Bernoulli elements
  with hinged ends condensed out (`ElementResult`), skewed supports solved in their own frame, a semi-definite
  Cholesky (`Cholesky`) whose null pivots are the mechanism's degrees of freedom. `ElementResult` gives the
  torseur de cohésion and the deflection anywhere along an element, exactly (not interpolated).
- `rdm.ui` — the Swing window (`MainFrame`, which also holds undo and solves after every edit), the drawing
  (`StructureCanvas`, `Symbols`), the properties panel (`Inspector`) and the text report (`Report`).

What comes from Schemes (schema-cinematique.com):
- **Supports.** The supports are the liaisons of `hyperstatism.ts` `jointBasis` with the bâti, cut down to the
  plane: each is defined by the components it transmits. An internal pivot becomes a hinge.
- **Hyperstatism.** Same definition as Schemes (unknowns − rank of the equilibrium equations, mobility = the
  nullity), computed here from the stiffness matrix: h = Σ(3 − hinges) − free unknowns + m.
- **Torseur de cohésion.** Same convention as `cohesion.ts`: action of the part after the cut on the part before,
  N > 0 in traction, dMf/dx = −T.
- **Sections and materials.** The list of `Section`/`mass.ts`, with E added.
- **Drawing.** The ISO 3952 drawing of the bâti and the pivot from `symbols.tsx`, and the encastrement turned to
  face its elements (`orient.ts`).

Hypotheses (also shown in the app): linear elasticity, small displacements, no shear deformation, constant
sections, loads at nodes or uniform along elements.

## Not yet

Point loads inside an element (place a node there instead), linearly varying loads, self-weight, imposed
displacements, thermal loads, elastic supports, buckling, 3D, shear deformation (Timoshenko), section
catalogues other than IPE, printing/export.
