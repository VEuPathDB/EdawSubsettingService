package org.veupathdb.service.edass.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.sql.DataSource;
import org.gusdb.fgputil.functional.TreeNode;
import org.veupathdb.service.edass.model.Variable.VariableType;

/**
 * A class to perform subsetting operations on a study entity
 * 
 * @author Steve
 *
 */
public class StudySubsettingUtils {

  private static final String nl = System.lineSeparator();

  public static void produceTabularSubset(DataSource datasource, Study study, Entity outputEntity,
      List<String> outputVariableNames, List<Filter> filters) {

    List<String> entityIdsInFilters = getEntityIdsInFilters(filters);

    TreeNode<Entity> prunedEntityTree = pruneTree(study.getEntityTree(), filters, outputEntity);
    
    String sql = generateTabularSql(outputVariableNames, outputEntity, filters, prunedEntityTree, entityIdsInFilters);

    // TODO run sql and produce stream output
  }
  
  
  public static void produceHistogramSubset(DataSource datasource, Study study, Entity outputEntity,
      Variable histogramVariable, List<Filter> filters) {

    List<String> entityIdsInFilters = getEntityIdsInFilters(filters);

    TreeNode<Entity> prunedEntityTree = pruneTree(study.getEntityTree(), filters, outputEntity);
    
    String sql = generateHistogramSql(outputEntity, histogramVariable, filters, prunedEntityTree, entityIdsInFilters);
    // TODO run sql and produce stream output
  }

  /**
   * Prune tree to include only active nodes, based on filters and output entity
   * @param tree
   * @param filters
   * @return
   */
  static TreeNode<Entity> pruneTree(TreeNode<Entity> tree, List<Filter> filters, Entity outputEntity) {

    List<String> entityIdsInFilters = getEntityIdsInFilters(filters);

    Predicate<Entity> isActive = e -> entityIdsInFilters.contains(e.getEntityId()) ||
        e.getEntityId().equals(outputEntity.getEntityId());
    return pruneToActiveAndPivotNodes(tree, isActive);
  }
  
  static List<String> getEntityIdsInFilters(List<Filter> filters) {
    return filters.stream().map(f -> f.getEntityId()).collect(Collectors.toList());
  }

  /**
   * Generate SQL to produce a multi-column tabular output (the requested variables), for the specified subset.
   * @param outputVariableNames
   * @param outputEntity
   * @param filters
   * @param prunedEntityTree
   * @param entityIdsInFilters
   * @return
   */
  static String generateTabularSql(List<String> outputVariableNames, Entity outputEntity, List<Filter> filters, TreeNode<Entity> prunedEntityTree, List<String> entityIdsInFilters) {

    return generateWithClauses(prunedEntityTree, filters, entityIdsInFilters) + nl
        + generateTabularSelectClause(outputEntity) + nl
        + generateFromClause(outputEntity) + nl
        + generateTabularWhereClause(outputVariableNames) + nl
        + generateInClause(prunedEntityTree, outputEntity) + nl
        + generateTabularOrderByClause(outputEntity) + nl;
  }

  /**
   * Generate SQL to produce a histogram for a single variable, for the specified subset.
   * @param outputVariableNames
   * @param outputEntity
   * @param filters
   * @param prunedEntityTree
   * @param entityIdsInFilters
   * @return
   */
  static String generateHistogramSql(Entity outputEntity, Variable histogramVariable, List<Filter> filters, TreeNode<Entity> prunedEntityTree, List<String> entityIdsInFilters) {
    
    List<String> outputVariableNames = new ArrayList<String>();
    outputVariableNames.add(outputEntity.getEntityPrimaryKeyColumnName());
    
    return generateWithClauses(prunedEntityTree, filters, entityIdsInFilters) + nl
        + generateHistogramSelectClause(histogramVariable) + nl
        + generateFromClause(outputEntity) + nl
        + generateHistogramWhereClause(histogramVariable) + nl
        + generateInClause(prunedEntityTree, outputEntity) + nl        
        + generateHistogramGroupByClause(histogramVariable) + nl;
   }
  
  static String generateWithClauses(TreeNode<Entity> prunedEntityTree, List<Filter> filters, List<String> entityIdsInFilters) {
    List<String> withClauses = prunedEntityTree.flatten().stream().map(e -> generateWithClause(e, filters)).collect(Collectors.toList());
    return "WITH" + nl
        + String.join("," + nl, withClauses);
  }
  
  /*
   * Get a with clause for this entity.  If the filters don't include any from this entity,
   * then the with clause will just select * from the entity's ancestor table
   */
  static String generateWithClause(Entity entity, List<Filter> filters) {

    // default WITH body assumes no filters. we use the ancestor table because it is small
    String withBody = "SELECT " + entity.getEntityPrimaryKeyColumnName() + " FROM " + entity.getEntityAncestorsTableName() + nl;
    
    List<Filter> filtersOnThisEnity = filters.stream().filter(f -> f.getEntityId().equals(entity.getEntityId())).collect(Collectors.toList());

    if (!filtersOnThisEnity.isEmpty()) {
      List<String> filterSqls = filters.stream().filter(f -> f.getEntityId().equals(entity.getEntityId())).map(f -> f.getSql()).collect(Collectors.toList());
      withBody = String.join("INTERSECT" + nl, filterSqls);
    } 

    return entity.getEntityName() + " as (" + nl + withBody + ")";
  }
  
  static String generateTabularSelectClause(Entity outputEntity) {
    // init list with pk columns, and add in the variable value columns
    List<String> colNames = new ArrayList<String>(outputEntity.getAncestorFullPkColNames());
    colNames.add(outputEntity.getEntityName() + "." + outputEntity.getEntityPrimaryKeyColumnName());
    for (VariableType varType : VariableType.values()) colNames.add(varType.getTallTableColumnName());

    String cols = String.join(", ", colNames);
    return "SELECT " + cols;
  }
    
  static String generateHistogramSelectClause(Variable histogramVariable) {
    return "SELECT count(" + histogramVariable.getName() + "), " + histogramVariable.getVariableType().getTallTableColumnName();
  }
  
  static String generateFromClause(Entity outputEntity) {
    return "FROM " + outputEntity.getEntityTallTableName();
  }
  
  static String generateTabularWhereClause(List<String> outputVariableNames) {
    return "WHERE (" + nl + "  "
        + String.join(nl + "  ", outputVariableNames)
        + ")";
  }
  
  static String generateHistogramWhereClause(Variable outputVariable) {
    return "WHERE ontology_term_name = '" + outputVariable.getName() + "'";
  }

  static String generateInClause(TreeNode<Entity> prunedEntityTree, Entity outputEntity) {
    return "AND " + outputEntity.getEntityName() + " IN (" 
    + generateInClauseSelectClause(outputEntity) + nl
    + generateInClauseFromClause(prunedEntityTree) + nl
    + generateInClauseJoinsClause(prunedEntityTree) + nl
    + ")";

  }
  
  static String generateInClauseSelectClause(Entity outputEntity) {
    return "  SELECT " + outputEntity.getEntityName() + "." + outputEntity.getEntityPrimaryKeyColumnName();
  }
  
  static String generateInClauseFromClause(TreeNode<Entity> prunedEntityTree) {
    List<String> fromClauses = prunedEntityTree.flatten().stream().map(e -> e.getEntityTallTableName() + " " + e.getEntityName()).collect(Collectors.toList());
    return "  FROM " + String.join(", ", fromClauses);
  }

  static String generateInClauseJoinsClause(TreeNode<Entity> prunedEntityTree) {
    List<String> sqlJoinStrings = new ArrayList<String>();
    addSqlJoinStrings(prunedEntityTree, sqlJoinStrings);
    return "  WHERE " + String.join(nl + "  AND ", sqlJoinStrings);    
  }
  
  /*
   * Add to the input list the sql join of a parent entity with each of its children, plus, recursively, its
   * children's sql joins
   */
  static void addSqlJoinStrings(TreeNode<Entity> parent, List<String> sqlJoinStrings) {
    for (TreeNode<Entity> child : parent.getChildNodes()) {
      sqlJoinStrings.add(getSqlJoinString(parent.getContents(), child.getContents()));
      addSqlJoinStrings(child, sqlJoinStrings);
    }
  }

  // this join is formed using the name from the WITH clause, which is the entity name
  static String getSqlJoinString(Entity parentEntity, Entity childEntity) {
    return parentEntity.getEntityName() + "." + parentEntity.getEntityPrimaryKeyColumnName() + " = " +
        childEntity.getEntityName() + "." + childEntity.getEntityPrimaryKeyColumnName();
  }
  
  static String generateTabularOrderByClause(Entity outputEntity) {
    return "ORDER BY " + outputEntity.getEntityPrimaryKeyColumnName();
  }
  
  static String generateHistogramGroupByClause(Variable outputVariable) {
    return "GROUP BY " + outputVariable.getVariableType().getTallTableColumnName();
  }
  

  /*
   * PRUNE THE COMPLETE TREE TO JUST THE "ACTIVE" ENTITIES WE WANT FOR OUR JOINS
   * 
   * definition: an active entity is one that must be included in the SQL definition: an active subtree is one
   * in which any entities in the subtree are active.
   * 
   * this entity is active if any of these apply: 1. it has filters 2. it is the output entity 3. it is
   * neither of the above, but has more than one child that is the root of an active subtree
   * 
   * (criterion 3 lets us join elements across connected subtrees)
   * 
   * ----X---- | | --I-- I | | | A I A
   * 
   * In the picture above the A entities are active and I are inactive. X has two children that are active
   * subtrees. We need to force X to be active so that we can join the lower A entities.
   *
   * So will we now have this:
   * 
   * ----A---- | | --I-- I | | | A I A
   * 
   * Finally, we want to prune the tree of inactive nodes, so we have the minimal active tree:
   * 
   * ----A---- | | A A
   * 
   * Now we can ascend the tree and form the concise SQL joins we need
   * 
   * Using a concrete example: ----H---- | | --P-- E | | | O S T
   * 
   * If O and and T are active (have filters or are the output entity), then we ultimately need this join:
   * where O.H_id = H.H_id and T.H_id = H.H_id
   * 
   * (The graceful implementation below is courtesy of Ryan)
   */

  private static <T> TreeNode<T> pruneToActiveAndPivotNodes(TreeNode<T> root, Predicate<T> isActive) {
    return root.mapStructure((nodeContents, mappedChildren) -> {
      List<TreeNode<T>> activeChildren = mappedChildren.stream().filter(child -> child != null) // filter dead
                                                                                                // branches
          .collect(Collectors.toList());
      return isActive.test(nodeContents) || activeChildren.size() > 1 ?
      // this node is active itself or a pivot node; return with any active children
      new TreeNode<T>(nodeContents).addAllChildNodes(activeChildren) :
      // inactive, non-pivot node; return single active child or null
      activeChildren.isEmpty() ? null : activeChildren.get(0);
    });
  }

}
