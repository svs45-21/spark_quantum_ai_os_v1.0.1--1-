/* Program 5 : Binary Search Tree (Insert, Delete, Search, Display)
   Compiler  : Turbo C++ 3.0                                       */
#include<iostream.h>
#include<conio.h>
#include<stdio.h>
#include<stdlib.h>

struct treeNode
{
  int data;
  treeNode *left;
  treeNode *right;
};

treeNode* FindMin(treeNode *node)
{
  if(node==NULL)
    return NULL;
  if(node->left)
    return FindMin(node->left);
  else
    return node;
}

treeNode* FindMax(treeNode *node)
{
  if(node==NULL)
    return NULL;
  if(node->right)
    return FindMax(node->right);
  else
    return node;
}

treeNode* Insert(treeNode *node, int data)
{
  if(node==NULL)
  {
    treeNode *temp;
    temp=new treeNode;
    temp->data=data;
    temp->left=temp->right=NULL;
    return temp;
  }
  if(data>(node->data))
    node->right=Insert(node->right,data);
  else if(data<(node->data))
    node->left=Insert(node->left,data);
  return node;
}

treeNode* Delet(treeNode *node, int data)
{
  treeNode *temp;
  if(node==NULL)
  {
    cout<<"Element Not Found";
  }
  else if(data<node->data)
  {
    node->left=Delet(node->left,data);
  }
  else if(data>node->data)
  {
    node->right=Delet(node->right,data);
  }
  else
  {
    if(node->right && node->left)
    {
      /* two children : replace with smallest in right subtree */
      temp=FindMin(node->right);
      node->data=temp->data;
      node->right=Delet(node->right,temp->data);
    }
    else
    {
      /* zero or one child */
      temp=node;
      if(node->left==NULL)
        node=node->right;
      else if(node->right==NULL)
        node=node->left;
      delete temp;
    }
  }
  return node;
}

treeNode* Find(treeNode *node, int data)
{
  if(node==NULL)
    return NULL;
  if(data>node->data)
    return Find(node->right,data);
  else if(data<node->data)
    return Find(node->left,data);
  else
    return node;
}

void disp(treeNode *node)      /* inorder traversal */
{
  if(node==NULL)
    return;
  disp(node->left);
  cout<<node->data<<" ";
  disp(node->right);
}

void main()
{
  treeNode *root=NULL, *temp;
  int ch, val;
  clrscr();
  while(1)
  {
    cout<<"\n**Binary Search Tree Operations**";
    cout<<"\n1.Insert\n2.Delete\n3.Search\n4.Exit\n";
    cout<<"Enter your choice: ";
    cin>>ch;
    switch(ch)
    {
      case 1: cout<<"Enter element to be inserted: ";
              cin>>val;
              root=Insert(root,val);
              cout<<"Elements in BST are: ";
              disp(root);
              break;
      case 2: cout<<"Enter element to be deleted: ";
              cin>>val;
              root=Delet(root,val);
              cout<<"\nAfter deletion elements in BST are: ";
              disp(root);
              break;
      case 3: cout<<"Enter element to be searched: ";
              cin>>val;
              temp=Find(root,val);
              if(temp==NULL)
                cout<<"Element is not found\n";
              else
                cout<<"Element "<<temp->data<<" is Found\n";
              break;
      case 4: exit(0);
      default: cout<<"Enter correct choice\n";
    }
  }
}
