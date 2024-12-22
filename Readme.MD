Run with following parameters:
```
sources
example/0-accesses.csv
example/0-names.csv
example/0-systems.csv
mappings
example/mapping.rml
shapes
example/shape1.shacl
example/shape2.shacl
queries
example/q1.sparql
example/q2.sparql
example/q3.sparql
runners
example/application1.py
example/application2.py
```

Known limitations:
 * Mappings must be converted to RDF already
 * Shapes must be in separated files and are analyzed using a generic query
 * Ontology is not considered
 * Python dependencies work on filename literals