import pandas as pd
from owlready2 import *
import types
import requests
import numpy as np

def toPascalCase(s):
    return ''.join(x for x in s.title() if not (x.isspace() or x == '.'))

def add_plants(onto,plants):
    with onto:
        Flora = types.new_class("Flora", (Thing,))
        # potatoClass = types.new_class("PotatoButInLatin", (Flora,))
        allPlantConcepts = dict()
        for p in plants:
            if (not pd.isna(p[1])):
                plant = types.new_class(toPascalCase(p[1]), (Flora,))  # class and IRI
                allPlantConcepts[p[1]] = plant
                plant.label = [locstr(p[0].title(), lang="en")]  # english label
                # plant. = [p[1].title()] #find how to add custom annotations
                # darwin.scientificName
                plant.scientificName = [p[1].title()]
                row = ntp[ntp.taxon == p[1]]
                if not row.empty:
                    plant.seeAlso = [row.iloc[0].plantWikidata]

                # neighbouring axioms
                gca = GeneralClassAxiom(onto.companionWith.some(plant) &
                                        onto.neighbour.some(plant))  # lhs
                gca.is_a.append(onto.companionNeighbour.some(plant))

                gca = GeneralClassAxiom(onto.anticompanionWith.some(plant) &
                                        onto.neighbour.some(plant))  # lhs
                gca.is_a.append(onto.incompatibleNeighbour.some(plant))

        AllDisjoint(list(allPlantConcepts.values()))
    
    return onto,allPlantConcepts

def add_companions(onto,df,allPlantConcepts):
    with onto:
        df = df[~((df.v1 == 'tomato') & (df.v2.isin(['beet','rue','tansy','thyme'])) & (df.rel == 'companion'))]

        for _, row in df.iterrows():
            if not (pd.isna(row.taxon_v1) or pd.isna(row.taxon_v2)):
                v1 = allPlantConcepts[row.taxon_v1]
                v2 = allPlantConcepts[row.taxon_v2]

                if row['rel'] == 'companion':
                    if len(v1.companionWith) == 0:
                        v1.companionWith = [v2]
                        #AnnotatedRelation(v1, companionWith, v2).comment = ['Companion planting chart']

                    else:
                        b=v1.companionWith.append(v2)
                        #AnnotatedRelation(v1, companionWith, v2).comment = ['Companion planting chart']

                if row['rel'] == 'antagonistic':
                    if len(v1.anticompanionWith) == 0:
                        v1.anticompanionWith = [v2]
                        #AnnotatedRelation(v1, anticompanionWith, v2).comment = ['Companion planting chart']

                    else:
                        v1.anticompanionWith.append(v2)
                        #AnnotatedRelation(v1, anticompanionWith, v2).comment = ['Companion planting chart']

    # onto.save(file='../owl/companion_planting_v5.owl')
    return onto

def add_interactions(onto,interaction_data):
    with onto:
        for row in interaction_data:

            try:
                if row[0][1] not in ['interactsWith']:
                    species = toPascalCase(row[0][0])
                    predicate = row[0][1]
                    if species.startswith(tuple(['UCSC', 'CLEMS', 'SBMNH', 'CUP'])):
                        pass
                    else:
                        species_class = types.new_class(species, (
                        onto.Species,))  # thing should be more specific, plant or other animal
                        # species_class.scientificName = row[0][0]
                        interaction_predicate = types.new_class(predicate, (ObjectProperty,))
                        interaction_predicate_inverse = types.new_class("_" + predicate, (ObjectProperty,))

                    for interacting_species in row[0][2]:
                        if interacting_species.startswith(tuple(['UCSC', 'CLEMS', 'SBMNH', 'CUP'])):
                            pass
                        else:
                            interacting_species_ln = interacting_species
                            interacting_species = toPascalCase(interacting_species)

                            interacting_species_class = types.new_class(interacting_species, (onto.Species,))
                            interacting_species_class.scientificName = interacting_species_ln
                            species_class.is_a.append(interaction_predicate.some(interacting_species_class))
                            interacting_species_class.is_a.append(interaction_predicate_inverse.some(species_class))
            except:
                pass
    return onto


if __name__ == "__main__":

    onto = get_ontology('example/copla/copla_base.owl').load()
    darwin = get_ontology('http://rs.tdwg.org/dwc/terms/')

    with darwin:
        class scientificName(AnnotationProperty):
            pass
    onto.imported_ontologies.append(darwin)

    # load the companion planting dataset/table
    df = pd.read_csv('example/copla/companion_plants_including_taxon.csv')

    # load the names-taxon-products dataframe: idx, taxon,plantCommonName,plantWikidata,productCommonName,productWikidata
    ntp = pd.read_csv('example/copla/names-taxon-products.csv')
    ntp['plantWikidata'] = ntp['plantWikidata'].apply(lambda x: x.replace('q', 'Q') if 'q' in x else x)
    # adding the various plants from the companion plant table
    plants = pd.concat([df[['v1', 'taxon_v1']].rename(columns={'v1': 'v', 'taxon_v1': 'taxon'}),
                    df[['v2', 'taxon_v2']].rename(columns={'v2': 'v', 'taxon_v2': 'taxon'})]).drop_duplicates().values


    onto,allPlantConcepts = add_plants(onto,plants)
    onto = add_companions(onto,df,allPlantConcepts)

    taxon_names = list(pd.concat([df['taxon_v1'], df['taxon_v2']]).drop_duplicates().dropna().values)
    interaction_data = []

    for taxon_name in taxon_names:
        taxon_name = taxon_name.replace(" ", "%20")
        URL = "https://api.globalbioticinteractions.org/taxon/" + taxon_name + "/interactsWith"
        try:
            interaction_data.append(requests.get(URL).json()['data'])
        except:
            pass
    
    onto = add_interactions(onto,interaction_data)
    onto.save("example/copla/copla_full.owl")
