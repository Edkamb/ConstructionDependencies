import pandas as pd
from owlready2 import *
import types
import requests


def toPascalCase(s):
    return ''.join(x for x in s.title() if not (x.isspace() or x == '.'))


import numpy as np

# create the links to the java gateway and parse the ontology

iri = 'http://www.semanticweb.org/kai/ontologies/2024/companion-planting#'
# partof = get_ontology('http://www.ontologydesignpatterns.org/cp/owl/partof.owl#')
# onto = get_ontology('../owl/companion-planting-base0.2.rdf').load()
onto = get_ontology('../owl/companion-planting-base0.2.rdf') # this makes an empty ontology, if the file doesn't exist
onto.base_iri = iri
darwin = get_ontology('http://rs.tdwg.org/dwc/terms/')

with darwin:
    class scientificName(AnnotationProperty):
        pass
onto.imported_ontologies.append(darwin)

'''
####################
CREATE BASE ONTOLOGY
####################
'''

# questions:
# 1. are attractorOf and visitedBy the same?
# 2. do we need to rename the companion properties?
# 3. increasedLevelInteraction == protectiveShelter?

# Object properties and property chains
with onto:
    # species class
    speciesParentClass = types.new_class('Species', (Thing,))
    # classes
    Garden = types.new_class("Garden", (Thing,))
    Flora = types.new_class("Flora", (speciesParentClass,))
    Fauna = types.new_class("Fauna", (speciesParentClass,))
    BadGarden = types.new_class("BadGarden", (Garden,))
    CompanionGarden = types.new_class("CompanionGarden", (Garden,))
    ThreeCompanionGarden = types.new_class("3CompanionGarden", (Garden,))
    ThreeTripleCompanionGarden = types.new_class("3TripleCompanionGarden", (Garden,))
    BadlyPlacedFlora = types.new_class("BadlyPlacedFlora", (Flora,))
    PlantWithCompanion = types.new_class("PlantWithCompanion", (Flora,))
    PlantWith2Companions = types.new_class("PlantWith2Companions", (Flora,))
    PlantWith3Companions = types.new_class("PlantWith3Companions", (Flora,))
    Fruit = types.new_class("Fruit", (Flora,))
    Predator = types.new_class("Predator", (Fauna,))
    Pollinator = types.new_class("Pollinator", (Fauna,))

    # companion properties (do we need all of these?)
    anticompanionWith = types.new_class("anticompanionWith", (ObjectProperty,))
    companionWith = types.new_class("companionWith", (ObjectProperty,))
    positiveHostingFor = types.new_class("positiveHostingFor", (companionWith,))
    recruitsPollinatorsFor = types.new_class("recruitsPollinatorsFor", (positiveHostingFor,))
    recruitsPredatorsFor = types.new_class("recruitsPredatorsFor", (positiveHostingFor,))
    trapCropFor = types.new_class("trapCropFor", (companionWith,))

    providesNutrientsFor = types.new_class("providesNutrientsFor", (companionWith,))
    providesNitrogenFor = types.new_class("providesNitrogenFor", (providesNutrientsFor,))
    providesCalcium = types.new_class("providesCalcium", (providesNutrientsFor,))
    providesPhosphorus = types.new_class("providesPhosphorus", (providesNutrientsFor,))
    providesPotassium = types.new_class("providesPotassium", (providesNutrientsFor,))
    providesWaterFor = types.new_class("providesWaterFor", (providesNutrientsFor,))
    physicalSupportFor = types.new_class("physicalSupportFor", (companionWith,))
    providesShadeFor = types.new_class("providesShadeFor", (physicalSupportFor,))
    providesWindProtectionFor = types.new_class("providesWindProtectionFor", (physicalSupportFor,))

    companionNeighbour = types.new_class("companionNeighbour", (ObjectProperty,))
    neighbour = types.new_class("neighbour", (ObjectProperty, SymmetricProperty))
    incompatibleNeighbour = types.new_class("incompatibleNeighbour", (ObjectProperty,))
    containsFlora = types.new_class("containsFlora", (ObjectProperty,))

    # interaction properties (globi)
    interactsWith = types.new_class("interactsWith", (ObjectProperty,))
    visitedBy = types.new_class("visitedBy", (interactsWith,))
    _visitedBy = types.new_class("_visitedBy", (interactsWith,))
    hasParasite = types.new_class("hasParasite", (interactsWith,))
    parasiteOf = types.new_class("parasiteOf", (interactsWith,))
    hostOf = types.new_class("hostOf", (interactsWith,))
    hasHost = types.new_class("hasHost", (interactsWith,))
    pollinatedBy = types.new_class("pollinatedBy", (interactsWith,))
    _pollinatedBy = types.new_class("_pollinatedBy", (interactsWith,))
    hasPathogen = types.new_class("hasPathogen", (interactsWith,))
    _hasPathogen = types.new_class("_hasPathogen", (interactsWith,))
    eatenBy = types.new_class("eatenBy", (interactsWith,))
    _eatenBy = types.new_class("_eatenBy", (interactsWith,))
    mutualistOf = types.new_class("mutualistOf", (interactsWith,))
    _mutualistOf = types.new_class("_mutualistOf", (interactsWith,))
    flowersVisitedBy = types.new_class("flowersVisitedBy", (interactsWith,))
    _flowersVisitedBy = types.new_class("_flowersVisitedBy", (interactsWith,))

    # other interaction properties
    repellerOf = types.new_class("repellerOf", (interactsWith,))
    isRepelledBy = types.new_class("isRepelledBy", (interactsWith,))

    # plant property predicates
    belongsToLayer = types.new_class("belongsToLayer", (ObjectProperty,))
    hasPart = types.new_class("hasPart", (ObjectProperty,))

    # property chains
    recruitsPredatorsFor.property_chain.append(PropertyChain([visitedBy, _eatenBy, parasiteOf]))
    recruitsPollinatorsFor.property_chain.append(PropertyChain([visitedBy, _pollinatedBy]))
    recruitsPollinatorsFor.property_chain.append(PropertyChain([flowersVisitedBy, _pollinatedBy]))
    repellerOf.property_chain.append(PropertyChain([hasPart, repellerOf]))

    # other axioms
    BadGarden.equivalent_to = [Garden & containsFlora.some(BadlyPlacedFlora)]
    CompanionGarden.equivalent_to = [Garden & containsFlora.some(PlantWithCompanion)]
    PlantWithCompanion.equivalent_to = [Flora & companionNeighbour.some(Flora)]
    PlantWith2Companions.equivalent_to = [Flora & companionNeighbour.min(2)]
    PlantWith3Companions.equivalent_to = [Flora & companionNeighbour.min(3)]
    ThreeCompanionGarden.equivalent_to = [Garden & containsFlora.min(3, PlantWithCompanion)]
    ThreeTripleCompanionGarden.equivalent_to = [Garden & containsFlora.min(3, PlantWith3Companions)]

onto.save("example/copla/copla_base.owl")