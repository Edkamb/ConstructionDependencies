import sys
from rdflib import Graph

def run_sparql_queries(rdf_file_path):
    # Array of SPARQL query file paths (hardcoded as a string literal)
    query_files = [
        "q2.sparql",
        "q3.sparql"
    ]
    
    # Load the RDF graph
    graph = Graph()
    graph.parse(rdf_file_path)
    
    # Process each query file
    for query_file in query_files:
        print(f"\nExecuting query from {query_file}:")
        print("-" * 50)
        
        # Read and execute the query
        try:
            with open(query_file, 'r') as f:
                query = f.read()
            
            results = graph.query(query)
            
            # Print the headers
            if results.vars:
                print(" | ".join(str(var) for var in results.vars))
                print("-" * 50)
            
            # Print each result row
            for row in results:
                # Convert each value to string, replacing None with 'None'
                row_values = [str(value) if value is not None else 'None' for value in row]
                print(" | ".join(row_values))
                
        except Exception as e:
            print(f"Error executing query {query_file}: {str(e)}")
        
        print("\n")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python script.py <path_to_rdf_file>")
        sys.exit(1)
    
    rdf_file_path = sys.argv[1]
    run_sparql_queries(rdf_file_path)