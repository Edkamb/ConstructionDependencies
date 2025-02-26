import pandas as pd
from owlready2 import *
import types
import requests
import numpy as np

def make_systems(onto,df):
    with onto:
    # add systems as individuals
        for row,sys in df.iterrows():
            new_sys = onto.System()
            print(new_sys)
            new_sys.systemId.append(sys['id'])
            new_sys.systemName.append(sys['name'])
            new_sys.location.append(sys['location'])

    return onto

def make_role(onto,roles):
    # add roles as individual
    with onto:
        for role in roles:
            new_role = onto.Role()
            new_role.roleName = role
    
    return onto

def make_users(onto,df):
    # add users as individuals
    with onto:
        for row,user in df.iterrows():    
            new_user = onto.User()
            new_user.userId.append(user['id'])
            new_user.name = user['first']+" "+user['last']
            # connect user to the existing role
            for role in onto.Role.instances():
                if role.roleName == user['role']:
                    new_user.hasRole.append(role)

    return onto

def make_accesses(onto,df):
    # add accesses
    with onto:
        for row,access in df.iterrows():
            a = onto.Access()
            a.accessedBy = onto.search(userId=access['userid'])
            a.accessed = onto.search(systemId=access['systemid'])
            a.at.append(access['date'])
    return onto




if __name__ == "__main__":

    path="file:///home/romi/Documents/Research/KGCDependencies/ConstructionDependencies/example/"
    onto = get_ontology(path+"ontology-rdf.owl").load()
    
    df_sys = pd.read_csv(path+'0-systems.csv')
    df_names = pd.read_csv(path+'0-names.csv')
    df_acc = pd.read_csv(path+'0-accesses.csv')

    roles = set(df_names['role'])
    roles.remove(np.nan)

    onto = make_systems(onto,df_sys)
    onto = make_role(onto,roles)
    onto = make_users(onto,df_names)
    onto = make_accesses(onto,df_acc)

    onto.save("ontology-populated.owl")
