package org.veupathdb.service.edass.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.sql.DataSource;
import javax.ws.rs.InternalServerErrorException;

import org.gusdb.fgputil.functional.TreeNode;
import org.veupathdb.service.edass.generated.model.APIDateRangeFilter;
import org.veupathdb.service.edass.generated.model.APIDateSetFilter;
import org.veupathdb.service.edass.generated.model.APIFilter;
import org.veupathdb.service.edass.generated.model.APINumberRangeFilter;
import org.veupathdb.service.edass.generated.model.APINumberSetFilter;
import org.veupathdb.service.edass.generated.model.APIStringSetFilter;

/**
 * A class to perform subsetting operations on a study entity
 * 
 * @author Steve
 *
 */
public class StudySubsetting {

  private static final String nl = System.lineSeparator();

  public static void produceSubset(DataSource datasource, Study study, Entity outputEntity,
      Set<String> outputVariableNames, Set<APIFilter> apiFilters) {

    Set<Filter> filters = constructFiltersFromAPIFilters(study, apiFilters);

    Set<String> entityIdsInFilters = filters.stream().map(f -> f.getEntityId()).collect(Collectors.toSet());

    Predicate<Entity> isActive = e -> entityIdsInFilters.contains(e.getEntityId()) ||
        e.getEntityId().equals(outputEntity.getEntityId());
    TreeNode<Entity> prunedEntityTree = pruneToActiveAndPivotNodes(study.getEntityTree(), isActive);
    
    String sql = generateSql(outputVariableNames, outputEntity, filters, prunedEntityTree, entityIdsInFilters);

  }
  
  private static String generateSql(Set<String> outputVariableNames, Entity outputEntity, Set<Filter> filters, TreeNode<Entity> prunedEntityTree, Set<String> entityIdsInFilters) {

    return generateWithClauses(prunedEntityTree, filters, entityIdsInFilters) + nl
        + generateSelectClause(outputVariableNames, outputEntity) + nl
        + generateFromClause(prunedEntityTree) + nl
        + generateJoinsClause(prunedEntityTree);
  }

  private static String generateSelectClause(Set<String> outputVariableNames, Entity outputEntity) {
    
    // init list with pk columns
    List<String> colNames = new ArrayList<String>(outputEntity.getAncestorFullPkColNames());
    
    // add in variables columns
    String outputEntityName = outputEntity.getEntityName();
    List<String> varCols = outputVariableNames.stream().map(v -> outputEntityName + "." + v).collect(Collectors.toList());
    colNames.addAll(varCols);

    String cols = String.join(", ", colNames);
    return "SELECT " + cols;
  }
  
  private static String generateFromClause(TreeNode<Entity> prunedEntityTree) {
    //TODO
    return null;
  }

  private static String generateWithClauses(TreeNode<Entity> prunedEntityTree, Set<Filter> filters, Set<String> entityIdsInFilters) {
    //TODO List<String> withClauses = prunedEntityTree.
    return null;
  }
  
  private static String generateWithClause(Entity entity, Set<Filter> filters) {

    // default WITH body assumes no filters. we use the ancestor table because it is small
    String withBody = "SELECT " + entity.getEntityPrimaryKeyColumnName() + " FROM " +
        entity.getEntityAncestorTableName();
    
    Set<Filter> filtersOnThisEnity = filters.stream().filter(f -> f.getEntityId().equals(entity.getEntityId())).collect(Collectors.toSet());

    if (!filtersOnThisEnity.isEmpty()) {
      Set<String> filterSqls = filters.stream().filter(f -> f.getEntityId().equals(entity.getEntityId())).map(f -> f.getSql()).collect(Collectors.toSet());
      withBody = String.join(nl + "INTERSECT" + nl, filterSqls);
    }

    return "WITH " + entity.getEntityName() + " as (" + nl + withBody + nl + ")";
  }
  
  private static String generateJoinsClause(TreeNode<Entity> prunedEntityTree) {
    List<String> sqlJoinStrings = new ArrayList<String>();
    addSqlJoinStrings(prunedEntityTree, sqlJoinStrings);
    return "WHERE " + String.join(nl + "AND ", sqlJoinStrings);    
  }
  
  /*
   * Add to the input list the sql join of a parent entity with each of its children, plus, recursively, its
   * children's sql joins
   */
  private static void addSqlJoinStrings(TreeNode<Entity> parent, List<String> sqlJoinStrings) {
    for (TreeNode<Entity> child : parent.getChildNodes()) {
      sqlJoinStrings.add(getSqlJoinString(parent.getContents(), child.getContents()));
      addSqlJoinStrings(child, sqlJoinStrings);
    }
  }

  // this join is formed using the name from the WITH clause, which is the entity name
  private static String getSqlJoinString(Entity parentEntity, Entity childEntity) {
    return parentEntity.getEntityName() + "." + parentEntity.getEntityPrimaryKeyColumnName() + " = " +
        childEntity.getEntityName() + "." + childEntity.getEntityPrimaryKeyColumnName();
  }
  
  /*
   * Given a study and a set of API filters, construct and return a set of filters, each being the appropriate
   * filter subclass
   */
  private static Set<Filter> constructFiltersFromAPIFilters(Study study, Set<APIFilter> filters) {
    Set<Filter> subsetFilters = new HashSet<Filter>();

    for (APIFilter filter : filters) {

      Entity entity = study.getEntity(filter.getEntityId());
      String id = entity.getEntityId();
      String pkCol = entity.getEntityPrimaryKeyColumnName();
      String table = entity.getEntityTallTableName();

      Filter newFilter;
      if (filter instanceof APIDateRangeFilter)
        newFilter = new DateRangeFilter((APIDateRangeFilter) filter, id, pkCol, table);
      else if (filter instanceof APIDateSetFilter)
        newFilter = new DateSetFilter((APIDateSetFilter) filter, id, pkCol, table);
      else if (filter instanceof APINumberRangeFilter)
        newFilter = new NumberRangeFilter((APINumberRangeFilter) filter, id, pkCol, table);
      else if (filter instanceof APINumberSetFilter)
        newFilter = new NumberSetFilter((APINumberSetFilter) filter, id, pkCol, table);
      else if (filter instanceof APIStringSetFilter)
        newFilter = new StringSetFilter((APIStringSetFilter) filter, id, pkCol, table);
      else
        throw new InternalServerErrorException("Input filter not an expected subclass of Filter");

      subsetFilters.add(newFilter);
    }
    return subsetFilters;
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
