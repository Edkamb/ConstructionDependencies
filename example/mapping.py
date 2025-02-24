import pandas as pd
from owlready2 import *
import types
import requests
import numpy as np
path="file:///home/romi/Documents/Research/KGCDependencies/ConstructionDependencies/example/"

# create the links to the java gateway and parse the ontology

# iri = 'http://www.semanticweb.org/kai/ontologies/2024/companion-planting#'
# partof = get_ontology('http://www.ontologydesignpatterns.org/cp/owl/partof.owl#')
# onto = get_ontology('../owl/companion-planting-base0.2.rdf').load()
# onto = get_ontology('../owl/companion-planting-base0.2.rdf')
# onto.base_iri = iri
# darwin = get_ontology('http://rs.tdwg.org/dwc/terms/')

# with darwin:
#     class scientificName(AnnotationProperty):
#         pass
# onto.imported_ontologies.append(darwin)


'''
####################
LOAD BASE ONTOLOGY
####################
'''

onto = get_ontology(path+"ontology-rdf.owl").load()
print(list(onto.classes()))
print(list(onto.object_properties()))
print(list(onto.data_properties()))
      

'''
####################
ADD AXIOMS
####################
'''

# load accesses dataset/table
df_sys = pd.read_csv(path+'0-systems.csv')
df_names = pd.read_csv(path+'0-names.csv')
df_acc = pd.read_csv(path+'0-accesses.csv')

roles = set(df_names['role'])
roles.remove(np.nan)


with onto:
    # add systems as individuals
    for row,sys in df_sys.iterrows():
        new_sys = onto.System()
        print(new_sys)
        new_sys.systemId.append(sys['id'])
        new_sys.systemName.append(sys[' name'])
        new_sys.location.append(sys[' location'])

    # add roles as individual
    for role in roles:
        new_role = onto.Role()
        new_role.roleName = role

    # add users as individuals
    for row,user in df_names.iterrows():    
        new_user = onto.User()
        new_user.userId.append(user['id'])
        new_user.name = user['first']+" "+user['last']
        
        # connect user to the existing role
        for role in onto.Role.instances():
            if role.roleName == user['role']:
                new_user.hasRole.append(role)

    # add accesses
    for row,access in df_acc.iterrows():
        a = onto.Access()
        a.accessedBy = onto.search(userId=access[' userid'])
        a.accessed = onto.search(systemId=access['systemid'])
        a.at.append(access[' date'])


onto.save("ontology-populated.owl")