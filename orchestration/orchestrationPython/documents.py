"""A small internal document library for the document-analysis subagent.

Made-up memos from a fictional energy consultancy. Only some subtopics have
documents (there is nothing on tidal or fusion), so the coordinator invokes the
document-analysis subagent only where it can add something - "dynamic subagent
selection" from the lesson.
"""

from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class Document:
    title: str
    keywords: tuple[str, ...]
    text: str


DOCUMENTS: tuple[Document, ...] = (
    Document(
        title="Q3 Solar Portfolio Review",
        keywords=("solar", "photovoltaic", "pv"),
        text=(
            "Our 3 utility-scale PV sites averaged a 24% capacity factor in Q3. Module prices fell 18% "
            "year on year, but grid-connection queues now delay new sites by 2-4 years. Bifacial modules "
            "on single-axis trackers produced 9% more energy than fixed-tilt arrays."
        ),
    ),
    Document(
        title="Offshore Wind Maintenance Report",
        keywords=("wind", "offshore", "turbine"),
        text=(
            "Turbine availability across the North Sea cluster was 96.1%. Gearbox failures caused 40% of "
            "downtime; the 15 MW direct-drive units had none. Jack-up vessel day rates rose 35%, which "
            "makes operations and maintenance the largest controllable cost after financing."
        ),
    ),
    Document(
        title="Geothermal Feasibility Study - Highland Site",
        keywords=("geothermal",),
        text=(
            "Exploration wells found 180 C brine at 2.8 km, which is suitable for a binary-cycle plant of "
            "about 25 MW. Drilling is 55% of capital cost and the main risk is a dry well. An enhanced "
            "geothermal system (EGS) pilot would widen the usable resource but needs induced-seismicity monitoring."
        ),
    ),
    Document(
        title="Biomass Supply Contract Review",
        keywords=("biomass", "bioenergy", "biofuel", "wood pellet"),
        text=(
            "The plant burns 1.2 Mt of wood pellets a year. Sustainability certification now covers 92% of "
            "supply. Pellet prices are linked to freight costs and moved +/-30% in 18 months. Carbon "
            "accounting is contested unless feedstock comes from residues rather than whole trees."
        ),
    ),
)


def find_documents(subtopic_name: str) -> list[Document]:
    """Documents whose keywords appear as whole words in the subtopic NAME.

    Matching on the name only keeps selection tight: a subtopic like "Off-grid energy access"
    whose description merely mentions solar should not pull in the solar portfolio memo.
    """
    name = subtopic_name.lower()
    return [d for d in DOCUMENTS if any(re.search(rf"\b{re.escape(k)}\b", name) for k in d.keywords)]
