import pandas as pd


def combItem(a, b):
    if a == b:
        return a
    if not pd.isnull(b):
        return b
    return a


'''
read the taxon-product input dataset,
structure: item - taxon - product - productLabel
'''
taxon = pd.read_csv('./../datasets/taxon-product.csv')

'''
read the companion-planting-wikidata-latin input dataset,
structure: name - id - latin
'''
cp = pd.read_csv('./../datasets/companion-planting-wikidata-latin.csv')

# preprocess some columns to align the datasets
cp = cp.rename(columns={'id': 'item'})
cp.item = cp.item.apply(lambda x: f'http://www.wikidata.org/entity/{x}')

# the names are converted in lower case, they will be capitalized when adding to the ontology
for column in cp.columns:
    cp[f'{column}'] = cp[f'{column}'].str.lower()
for column in taxon.columns:
    taxon[f'{column}'] = taxon[f'{column}'].str.lower()

# perform the join operations
merged = taxon.merge(cp, how='outer', on='item')
merged = merged[['item', 'taxon', 'latin', 'name', 'product', 'productLabel']]

merged['taxon'] = merged.apply(lambda x: combItem(x.taxon, x.latin), axis=1)
result = merged.drop(['latin'], axis=1)[['taxon', 'name', 'item', 'productLabel', 'product']]
result = result.rename(columns={'name': 'plantCommonName', 'item': 'plantWikidata', 'productLabel': 'productCommonName',
                                'product': 'productWikidata'}).drop_duplicates()

# save to file
result.to_csv('./../datasets/Processed/names-taxon-products.csv')

# 'hacky' way to deal with some plural/singular discrepancies (duplicate dataset removing the last letters)
r2 = result.copy()
r2.plantCommonName = r2.plantCommonName.apply(lambda x: x[0:-1])
r2 = pd.concat([result, r2])[['plantCommonName', 'taxon']]

'''
read and preprocess the companion-planting input file
structure: plant1 - plant2 - relation
'''
comp = pd.read_csv('./../datasets/companion-planting.csv', names=['v1', 'v2', 'rel'], sep=';')
comp.v1 = comp.v1.str.lower()
comp.v2 = comp.v2.str.lower()

# join on the names in the companion planting file
comp1 = comp.merge(r2, how='left', left_on='v1', right_on='plantCommonName')
comp2 = comp1.merge(r2, how='left', left_on='v2', right_on='plantCommonName', suffixes=('_v1', '_v2')).drop_duplicates()

comp2[['v1', 'taxon_v1', 'v2', 'taxon_v2', 'rel']].drop_duplicates().to_csv('./../datasets/Processed'
                                                                            '/companion_plants_including_taxon.csv')
